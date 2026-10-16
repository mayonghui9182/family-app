"""模板消息推送（Change 12）：渲染/批量端点/错误码重试/降级/隔离。"""
import json
from collections.abc import Callable
from typing import Any

import httpx

from .conftest import make_settings


def tpl_settings(**overrides: Any):
    base = {
        "wechat_tpl_task_digest_id": "TPL-DIGEST",
        "wechat_tpl_summary_id": "TPL-SUMMARY",
        "wechat_tpl_task_assign_id": "TPL-ASSIGN",
        "wechat_tpl_event_digest_id": "TPL-EVENT",
    }
    base.update(overrides)
    return make_settings(**base)


def wechat_tpl_handler(seen: dict[str, Any], send_behavior: Callable[[int], httpx.Response] | None = None):
    """记录 token/send 调用次数与报文；send_behavior(第几次调用) 可定制返回。"""

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/cgi-bin/token":
            seen["token"] = seen.get("token", 0) + 1
            tok = "TOKEN-2" if seen["token"] > 1 else "TOKEN-1"
            return httpx.Response(200, json={"access_token": tok, "expires_in": 7200})
        if request.url.path == "/cgi-bin/message/template/send":
            seen["send"] = seen.get("send", 0) + 1
            seen.setdefault("bodies", []).append(json.loads(request.content))
            seen.setdefault("tokens", []).append(request.url.params.get("access_token"))
            if send_behavior is not None:
                return send_behavior(seen["send"])
            return httpx.Response(200, json={"errcode": 0, "errmsg": "ok", "msgid": 999})
        return httpx.Response(404)

    return handler


SECRET = {"X-Internal-Secret": "itest-secret"}


async def test_digest_and_assign_render_chinese_fields_with_tasks_url(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(
        wechat_handler=wechat_tpl_handler(seen),
        settings=tpl_settings(),
    )

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={
            "messages": [
                {"openid": "oDIGEST", "event": "task_digest",
                 "params": {"today_due": 3, "overdue": 1, "family_name": "老王家"}},
                {"openid": "oASSIGN", "event": "task_assigned",
                 "params": {"title": "买菜", "due_date": "2026-09-07"}},
            ]
        },
    )

    assert resp.status_code == 200
    results = resp.json()["results"]
    assert [r["openid"] for r in results] == ["oDIGEST", "oASSIGN"]
    assert all(r["ok"] for r in results)

    digest_body, assign_body = seen["bodies"]
    assert digest_body["template_id"] == "TPL-DIGEST"
    assert digest_body["touser"] == "oDIGEST"
    assert digest_body["url"].endswith("/tasks")
    assert "3" in digest_body["data"]["keyword1"]["value"]
    assert "1" in digest_body["data"]["keyword2"]["value"]
    assert digest_body["data"]["first"]["value"] == "今日家庭任务早报"

    assert assign_body["template_id"] == "TPL-ASSIGN"
    assert assign_body["url"].endswith("/tasks")
    assert assign_body["data"]["keyword1"]["value"] == "买菜"
    assert assign_body["data"]["keyword2"]["value"] == "2026-09-07"


async def test_summary_ready_uses_summary_template_and_dashboard_url(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen), settings=tpl_settings())

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oSUM", "event": "summary_ready",
             "params": {"family_name": "老王家", "date": "2026-09-06"}},
        ]},
    )

    assert resp.status_code == 200
    assert resp.json()["results"][0]["ok"] is True
    body = seen["bodies"][0]
    assert body["template_id"] == "TPL-SUMMARY"
    assert body["url"].endswith("/dashboard")
    assert body["data"]["keyword1"]["value"] == "老王家"
    assert body["data"]["keyword2"]["value"] == "2026-09-06"


# ---- Change 13 event_digest 日程早报渲染 ----


async def test_event_digest_renders_with_calendar_url(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen), settings=tpl_settings())

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oEVENT", "event": "event_digest",
             "params": {"event_count": 2, "event_summary": "08:00 吃药、15:00 复查"}},
        ]},
    )

    assert resp.status_code == 200
    assert resp.json()["results"][0]["ok"] is True
    body = seen["bodies"][0]
    assert body["template_id"] == "TPL-EVENT"
    # Change 13 Task 8：/calendar 路由已上线，断言完整 url
    # （原先 endswith("/") 的弱断言漏掉了路径缺失——首页也满足该断言）
    assert body["url"] == "https://tunnel.example.com/calendar"
    assert body["data"]["first"]["value"] == "今日家庭日程早报"
    assert body["data"]["keyword1"]["value"] == "今日 2 件安排"
    assert body["data"]["keyword2"]["value"] == "08:00 吃药、15:00 复查"
    assert body["data"]["remark"]["value"] == "点击查看今日日程"


async def test_event_digest_unconfigured_template_skipped(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(
        wechat_handler=wechat_tpl_handler(seen),
        settings=tpl_settings(wechat_tpl_event_digest_id=""),  # 日程早报模板未配置
    )

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oSKIP", "event": "event_digest",
             "params": {"event_count": 1, "event_summary": "09:00 散步"}},
            {"openid": "oOK", "event": "task_assigned", "params": {"title": "买菜"}},
        ]},
    )

    assert resp.status_code == 200
    results = resp.json()["results"]
    assert results[0]["ok"] is False and results[0]["skipped"] is True
    assert results[0]["errmsg"] == "template_not_configured"
    assert results[1]["ok"] is True
    # 只有配置齐全的第二条真正调了微信
    assert seen["send"] == 1
    assert seen["bodies"][0]["touser"] == "oOK"


async def test_event_digest_long_summary_truncated(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen), settings=tpl_settings())

    long_summary = "早" * 41  # 超过 keyword 位安全长度 40 → 截断为 39 + 省略号
    exact_40 = "晚" * 40      # 恰好 40 字符 → 不截断

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oLONG", "event": "event_digest",
             "params": {"event_count": 9, "event_summary": long_summary}},
            {"openid": "oEDGE", "event": "event_digest",
             "params": {"event_count": 1, "event_summary": exact_40}},
        ]},
    )

    assert resp.status_code == 200
    assert all(r["ok"] for r in resp.json()["results"])
    long_body, edge_body = seen["bodies"]
    assert long_body["data"]["keyword2"]["value"] == "早" * 39 + "…"
    assert edge_body["data"]["keyword2"]["value"] == exact_40


async def test_event_digest_over_five_entries_hit_truncation(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen), settings=tpl_settings())

    # 后端契约（CalendarReminderJobs.buildSummary）：超 5 条时取前 5 条 join("、") 后直接追加「等」，
    # 顿号不会出现在「等」之前；件数由 event_count 单独承载，两者条数允许不一致。
    # V11 约束下每行必带 HH:MM（event_time NOT NULL + 正则 CHECK + 创建请求 @NotBlank），
    # 真实超5条早报必进截断分支——本用例即覆盖该叠加路径（5 条带时间摘要实测 47 字符 > 40
    # → 渲染层取前 39 字符 + 省略号，见 template.py 的 len(summary) > 40 分支）
    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oMANY", "event": "event_digest",
             "params": {"event_count": 8,
                        "event_summary": "07:30 喂药、08:00 量血压、09:00 散步、10:00 买菜、11:00 接孙子等"}},
        ]},
    )

    assert resp.status_code == 200
    assert resp.json()["results"][0]["ok"] is True
    body = seen["bodies"][0]
    assert body["data"]["keyword1"]["value"] == "今日 8 件安排"
    assert body["data"]["keyword2"]["value"] == "07:30 喂药、08:00 量血压、09:00 散步、10:00 买菜、11…"


async def test_event_digest_few_entries_rendered_verbatim(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen), settings=tpl_settings())

    # 未超 5 条（buildSummary 直接 join）且实测 26 字符 ≤ 40 → 渲染层原样透传，不加省略号
    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oFEW", "event": "event_digest",
             "params": {"event_count": 3, "event_summary": "07:30 喂药、09:00 散步、19:00 遛狗"}},
        ]},
    )

    assert resp.status_code == 200
    assert resp.json()["results"][0]["ok"] is True
    body = seen["bodies"][0]
    assert body["data"]["keyword1"]["value"] == "今日 3 件安排"
    assert body["data"]["keyword2"]["value"] == "07:30 喂药、09:00 散步、19:00 遛狗"


async def test_template_send_without_secret_returns_403(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen), settings=tpl_settings())

    resp = await client.post("/internal/template/send", json={"messages": []})

    assert resp.status_code == 403
    assert resp.json()["code"] == 40300
    assert seen.get("send", 0) == 0


async def test_unknown_event_item_fails_without_any_wechat_call(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen), settings=tpl_settings())

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [{"openid": "oX", "event": "bogus_event", "params": {}}]},
    )

    assert resp.status_code == 200
    result = resp.json()["results"][0]
    assert result["ok"] is False
    assert "bogus_event" in (result["errmsg"] or "")
    assert seen.get("send", 0) == 0
    assert seen.get("token", 0) == 0


async def test_unconfigured_template_skipped_but_other_events_send(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(
        wechat_handler=wechat_tpl_handler(seen),
        settings=tpl_settings(wechat_tpl_task_digest_id=""),  # 早报模板未配置
    )

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oSKIP", "event": "task_digest", "params": {"today_due": 1, "overdue": 0}},
            {"openid": "oOK", "event": "task_assigned", "params": {"title": "倒垃圾"}},
        ]},
    )

    assert resp.status_code == 200
    results = resp.json()["results"]
    assert results[0]["ok"] is False and results[0]["skipped"] is True
    assert results[0]["errmsg"] == "template_not_configured"
    assert results[1]["ok"] is True
    # 只有第二条真正调了微信（token + 各一次 send）
    assert seen["send"] == 1
    assert seen["bodies"][0]["touser"] == "oOK"
    # 无截止日期降级文案
    assert seen["bodies"][0]["data"]["keyword2"]["value"] == "无截止日期"


async def test_43004_unsubscribed_not_retried(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(
        wechat_handler=wechat_tpl_handler(
            seen, lambda _n: httpx.Response(200, json={"errcode": 43004, "errmsg": "require subscribe"})
        ),
        settings=tpl_settings(),
    )

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [{"openid": "oGONE", "event": "task_assigned", "params": {"title": "x"}}]},
    )

    result = resp.json()["results"][0]
    assert result["ok"] is False
    assert result["errcode"] == 43004
    assert seen["send"] == 1  # 不重试


async def test_40001_token_invalid_refreshes_and_retries_once(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}

    def behavior(n: int) -> httpx.Response:
        if n == 1:
            return httpx.Response(200, json={"errcode": 40001, "errmsg": "invalid credential"})
        return httpx.Response(200, json={"errcode": 0, "errmsg": "ok", "msgid": 1})

    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen, behavior), settings=tpl_settings())

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [{"openid": "oR", "event": "task_digest", "params": {"today_due": 1, "overdue": 0}}]},
    )

    result = resp.json()["results"][0]
    assert result["ok"] is True
    assert seen["token"] == 2  # 初次 + 强制刷新
    assert seen["send"] == 2
    assert seen["tokens"] == ["TOKEN-1", "TOKEN-2"]


async def test_wechat_500_isolated_per_item_endpoint_still_200(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}

    def behavior(n: int) -> httpx.Response:
        return httpx.Response(500) if n == 1 else httpx.Response(200, json={"errcode": 0})

    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen, behavior), settings=tpl_settings())

    resp = await client.post(
        "/internal/template/send",
        headers=SECRET,
        json={"messages": [
            {"openid": "oBAD", "event": "task_assigned", "params": {"title": "a"}},
            {"openid": "oGOOD", "event": "task_assigned", "params": {"title": "b"}},
        ]},
    )

    assert resp.status_code == 200
    results = resp.json()["results"]
    assert results[0]["ok"] is False and "网络异常" in (results[0]["errmsg"] or "")
    assert results[1]["ok"] is True


async def test_malformed_body_returns_400(asgi_factory) -> None:  # type: ignore[no-untyped-def]
    seen: dict[str, Any] = {}
    client = asgi_factory(wechat_handler=wechat_tpl_handler(seen), settings=tpl_settings())

    resp = await client.post("/internal/template/send", headers=SECRET, json={"wrong": "shape"})

    assert resp.status_code == 400
    assert seen.get("send", 0) == 0
