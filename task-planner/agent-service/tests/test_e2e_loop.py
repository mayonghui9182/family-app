"""端到端集成测试（tasks.md 10.1）：会话拆解 → 用户确认执行 → 每日总结学习写回。

与其他单测最大的区别是**不打桩数据通道**：

- agent-service 走进程内 ASGI（`ASGITransport`），其内部 `call_tool` 经真实 MCP
  HTTP 打到 task-service，最终落到 compose 内的真实 postgres；
- task-service 的 REST 走真实 HTTP，地址由 `MCP_TASK_SERVICE_URL` 去掉 `/mcp`
  推导（REST 与 MCP 同进程同端口，避免再引入一个环境变量）；
- 只有 LLM 被打桩：`src.routes.chat._build_llm` 与
  `src.services.summary_learn._build_llm` 两处工厂换成脚本化 transport。

本测试只对显式允许写入的隔离测试服务运行。未设置 TP_E2E_ALLOW_WRITES=1 时
跳过；显式启用后服务不可达则失败，禁止把缺少集成环境当作验收通过。

链路（tasks.md 10.1 原文顺序）：
`POST /api/chat` 建 draft → `PUT /api/tasks/{id}` 确认 pending → 用户手动加一条
子待办 → 子待办逐个 completed → 任务 completed → `POST /api/summary/trigger` →
断言模板 `version+1`、版本行 `change_summary` 含 reason、`summary_execution_log`
有一条 `manual/success`。

运行（agent-service 目录下，`-s` 才能看到各阶段断言明细）：

    PYTHONPATH=. MCP_TASK_SERVICE_URL=http://localhost:8100/mcp \
        python -m pytest tests/test_e2e_loop.py -q -s
"""

from __future__ import annotations

import os
import time
import uuid
from typing import Any

import pytest
from conftest import ScriptedTransport, parse_sse
from httpx import ASGITransport, AsyncClient

from src.config import settings
from src.llm.client import LLMClient
from src.main import app
from src.services.mcp_gateway import call_tool

# 预置模板的常见步骤：与桩 LLM 拆解出的子待办标题逐字一致，
# 这样 diff 的「用户删除」为空，学习证据只剩用户手动新增的那一条
BASE_STEPS = ["确定演示主题", "制作演示PPT", "现场彩排"]
# 用户手动新增的步骤：REST 创建 → is_ai_generated=false → 采集判定为「用户新增」
USER_ADDED_STEP = "邀请媒体"
# 学习理由：写回后必须出现在版本行 change_summary 中（tasks.md 10.1 断言项）
LEARN_REASON = "两条任务都在彩排后补充了媒体邀请环节"
# 用户输入（拆解请求原文）
CHAT_MESSAGE = "帮我准备下周三的产品发布会"

# summary.j2 的用途标识与分类行前缀（与 conftest._PURPOSE_MARKERS 同源）
_SUMMARY_MARKER = "任务模板学习助手"
_CATEGORY_LINE = "## 分类："
# 自动学习的摘要前缀（summary_learn.AUTO_LEARN_PREFIX，此处内联避免跨模块取私有常量）
_AUTO_LEARN_PREFIX = "每日总结自动学习："


def _rest_base_url() -> str:
    """task-service REST 入口：由 MCP 地址去掉 `/mcp` 后缀推导。"""
    url = settings.mcp_task_service_url.rstrip("/")
    return url[: -len("/mcp")] if url.endswith("/mcp") else url


class _LoopTransport(ScriptedTransport):
    """e2e 桩 LLM：按 prompt 里的分类名分流总结学习的输出。

    采集窗口是「当天 0:00 至执行时刻」，真实库里同窗口内可能还有别的任务发生
    变动（例如手工冒烟留下的数据），它们会各自形成一个分类组、各触发一次学习
    调用。若对所有分类都返回 add_step，既会耗尽脚本队列，也会把步骤写进无关
    分类的模板。因此这里在每次调用前**重置** summary 队列：目标分类给学习脚本，
    其余分类给空更新（`_content_changed` 判定无变化 → 不写库、不计入
    categories_updated）。重置而非追加，保证与分类处理顺序无关。
    """

    def __init__(self, scripts: dict[str, list[Any]], target_category: str, target_update: dict[str, Any]) -> None:
        super().__init__(scripts)
        self._target = target_category
        self._target_update = target_update

    async def _create(self, **kwargs: Any) -> Any:
        system = kwargs["messages"][0]["content"]
        if _SUMMARY_MARKER in system:
            is_target = f"{_CATEGORY_LINE}{self._target}" in system
            payload = self._target_update if is_target else {"template_updates": [], "new_notes": ""}
            self._scripts["summary"] = [dict(payload)]
        return await super()._create(**kwargs)


async def _rest(client: AsyncClient, method: str, path: str, **kwargs: Any) -> Any:
    """task-service REST 调用 + 统一封套解包（`{"code","msg","data"}`）。

    任何 HTTP 4xx/5xx 或业务码非 0 都直接失败，并带上响应片段与中文 msg，
    便于定位是状态机拒绝、唯一性冲突还是资源不存在。
    """
    response = await client.request(method, path, **kwargs)
    assert response.status_code < 400, (
        f"{method} {path} → HTTP {response.status_code}：{response.text[:300]}"
    )
    body = response.json()
    assert body.get("code") == 0, f"{method} {path} → 业务码 {body.get('code')}：{body.get('msg')}"
    return body.get("data")


@pytest.fixture
def created_tasks() -> list[str]:
    """本次运行创建的任务 id：收尾逐个删除，保持数据库整洁。"""
    return []


@pytest.fixture
async def rest(created_tasks: list[str]) -> AsyncClient:
    """task-service 真实 REST 客户端；服务不可达时整份测试 skip。"""
    if os.environ.get("TP_E2E_ALLOW_WRITES") != "1":
        pytest.skip("E2E 必须显式设置 TP_E2E_ALLOW_WRITES=1 并连接隔离测试服务")
    base_url = _rest_base_url()
    async with AsyncClient(base_url=base_url, timeout=30.0,
                           headers={"X-Access-Token": os.environ.get("TP_E2E_ACCESS_TOKEN", "a" * 64)}) as client:
        try:
            response = await client.get("/healthz")
        except Exception as exc:  # 连接拒绝/DNS 失败等：环境未就绪，跳过而非失败
            pytest.fail(f"隔离 task-service 不可达（{base_url}）：{exc}")
        if response.status_code != 200:
            pytest.fail(f"task-service 健康检查未通过：HTTP {response.status_code}")
        yield client
        # 收尾：任务可级联删除；模板与分类没有删除端点，靠唯一名隔离重复运行
        for task_id in created_tasks:
            await client.request("DELETE", f"/api/tasks/{task_id}")


@pytest.fixture
async def agent() -> AsyncClient:
    """agent-service 进程内客户端（不触发 lifespan，定时器不会启动）。"""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test", timeout=180.0) as client:
        yield client


async def test_e2e_chat_to_template_learning(
    rest: AsyncClient,
    agent: AsyncClient,
    created_tasks: list[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """全链路：会话拆解建 draft → 确认执行 → 每日总结把用户新增沉淀进模板。"""
    # ---- 阶段 0：唯一名（分类名全局唯一，且模板/分类无删除端点） ----
    category_name = f"e2e验证分类{time.strftime('%m%d%H%M%S')}{uuid.uuid4().hex[:6]}"
    print(f"\n[e2e] 目标分类名（唯一）：{category_name}")

    # ---- 阶段 1：预置分类与模板 ----
    # 模板只能 POST 预置：当天若用 PUT 编辑，会写入 change_summary="用户手动编辑"
    # 的版本行，触发 9.4 的抑制策略——写回摘要变成固定文案「自动补充（用户已手动
    # 编辑，未覆盖）」，不含 reason，10.1 要求的断言就无法成立。
    category = await _rest(
        rest, "POST", "/api/categories",
        json={"name": category_name, "description": "端到端集成测试专用分类"},
    )
    category_id = category["id"]
    template = await _rest(
        rest, "POST", "/api/templates",
        json={
            "name": f"{category_name}拆解模板",
            "type": "decomposition",
            "category_id": category_id,
            "common_steps": BASE_STEPS,
            "estimated_subtask_count": len(BASE_STEPS),
            "notes": "",
        },
    )
    template_id = template["id"]
    initial_version = template["version"]
    assert initial_version == 1, f"新建模板应从 version=1 开始，实际 {initial_version}"
    print(f"[e2e] 预置分类 id={category_id}")
    print(f"[e2e] 预置模板 id={template_id} version={initial_version} common_steps={BASE_STEPS}")

    # ---- 阶段 2：安装桩 LLM（两处工厂；数据通道不打桩） ----
    stub = _LoopTransport(
        {
            "classification": [{"category": category_name, "confidence": 0.92}],
            "template_match": [{"matched": True, "template_id": template_id}],
            "decomposition": [
                {
                    "task_title": "准备产品发布会",
                    "task_description": "下周三产品发布会的完整准备工作",
                    "subtasks": [
                        {"title": step, "description": "", "notes": ""} for step in BASE_STEPS
                    ],
                    "agent_reasoning": "按发布会准备顺序拆成内容确定、材料制作、现场彩排三个环节。",
                }
            ],
        },
        target_category=category_name,
        # 模型即使声称有重复，也必须由服务端核对两个真实任务的证据。
        target_update={
            "template_updates": [
                {"action": "add_step", "step": USER_ADDED_STEP, "reason": LEARN_REASON}
            ],
            "new_notes": "",
        },
    )
    monkeypatch.setattr("src.routes.chat._build_llm", lambda: LLMClient(transport=stub))
    monkeypatch.setattr("src.services.summary_learn._build_llm", lambda: LLMClient(transport=stub))

    # ---- 阶段 3：POST /api/chat → SSE → draft 提案落库 ----
    response = await agent.post("/api/chat", json={"message": CHAT_MESSAGE})
    assert response.status_code == 200, f"/api/chat → HTTP {response.status_code}：{response.text[:300]}"
    events = parse_sse(response.text)
    names = [name for name, _ in events]
    assert names[0] == "classification", f"首个事件应是 classification，实际 {names[:3]}"
    assert names[-1] == "done", f"末个事件应是 done，实际 {names[-3:]}"
    assert "proposal" in names, f"缺少 proposal 事件：{names}"
    proposal = next(data for name, data in events if name == "proposal")
    task_id = proposal["task_id"]
    created_tasks.append(task_id)
    assert proposal["category"] == category_name, (
        f"分类应为 {category_name}，实际 {proposal['category']}"
    )
    assert [item["title"] for item in proposal["subtasks"]] == BASE_STEPS
    assert proposal["expires_at"], "draft 提案必须带 24h 过期时间"
    print(f"[e2e] SSE 事件序：{names}")
    print(f"[e2e] 建 draft 成功 task_id={task_id} expires_at={proposal['expires_at']}")

    # ---- 阶段 4：用户确认提案 draft → pending（清空 expires_at） ----
    draft = await _rest(rest, "GET", f"/api/tasks/{task_id}")
    assert draft["status"] == "draft", f"落库初始状态应为 draft，实际 {draft['status']}"
    assert draft["source"] == "agent", f"来源应为 agent，实际 {draft['source']}"
    confirmed = await _rest(rest, "PUT", f"/api/tasks/{task_id}", json={"status": "pending"})
    assert confirmed["status"] == "pending"
    assert confirmed["expires_at"] is None, "draft→pending 确认后必须清空过期时间"
    print(f"[e2e] 确认提案：draft → pending，expires_at 已清空")

    # ---- 阶段 5：用户手动新增一条子待办（is_ai_generated=false） ----
    added = await _rest(
        rest, "POST", f"/api/tasks/{task_id}/subtasks",
        json={
            "title": USER_ADDED_STEP,
            "description": "联系行业媒体与本地自媒体到场",
            "sort_order": len(BASE_STEPS) + 1,
        },
    )
    assert added["is_ai_generated"] is False, "REST 手动创建的子待办必须标记为非 AI 生成"
    print(f"[e2e] 手动新增子待办：{USER_ADDED_STEP}（is_ai_generated=False）")

    # ---- 阶段 6：子待办逐个 completed，随后任务 completed ----
    detail = await _rest(rest, "GET", f"/api/tasks/{task_id}")
    assert len(detail["subtasks"]) == len(BASE_STEPS) + 1
    for subtask in detail["subtasks"]:
        done = await _rest(
            rest, "PUT", f"/api/subtasks/{subtask['id']}", json={"status": "completed"}
        )
        assert done["status"] == "completed", f"子待办 {subtask['title']} 未完成"
    finished = await _rest(rest, "PUT", f"/api/tasks/{task_id}", json={"status": "completed"})
    assert finished["status"] == "completed"
    print(f"[e2e] {len(detail['subtasks'])} 条子待办全部 completed，任务状态 → completed")

    # 先验证单个真实样本不会更新，即使模型已经输出了 add_step。
    single = await agent.post("/api/summary/trigger", json={"force": True})
    assert single.json()["status"] == "success"
    assert category_name not in single.json()["categories_updated"]

    # 再创建第二条有原始快照的独立任务，重复相同的用户修改。
    second = await call_tool("create_task", {"title": "第二场产品发布会",
        "category_id": category_id, "status": "draft", "source": "agent"})
    created_tasks.append(second["id"])
    await call_tool("create_subtasks", {"task_id": second["id"],
        "template_id": template_id, "template_version": initial_version,
        "subtasks": [{"title": step, "notes": "确认设备"} for step in BASE_STEPS]})
    await _rest(rest, "PUT", f"/api/tasks/{second['id']}", json={"status": "pending"})
    await _rest(rest, "POST", f"/api/tasks/{second['id']}/subtasks", json={"title": USER_ADDED_STEP})
    second_detail = await _rest(rest, "GET", f"/api/tasks/{second['id']}")
    assert second_detail["ai_snapshot"]["template_id"] == template_id
    assert second_detail["subtasks"][1]["notes"][0]["content"] == "确认设备"
    await _rest(rest, "PUT", f"/api/tasks/{second['id']}", json={"status": "completed"})

    # ---- 阶段 7：手动触发每日总结（force=true 绕过「当天已成功」的 409） ----
    trigger = await agent.post("/api/summary/trigger", json={"force": True})
    assert trigger.status_code == 200, f"触发应返回 200，实际 {trigger.status_code}：{trigger.text[:300]}"
    # 注意：agent-service 的触发接口不套 {"code","msg","data"} 封套，直接返回结果对象
    result = trigger.json()
    assert result["status"] == "success", f"总结运行失败：{result.get('error_message')}"
    assert result["trigger_source"] == "manual"
    assert result["error_message"] is None
    assert category_name in result["categories_updated"], (
        f"categories_updated 应含目标分类，实际 {result['categories_updated']}"
    )
    print(
        f"[e2e] 触发总结：status={result['status']} attempts={result['attempts']} "
        f"window_since={result['window_since']} cancelled_drafts={result['cancelled_drafts']}"
    )
    print(f"[e2e] categories_updated={result['categories_updated']}")

    # ---- 阶段 8：模板 version+1，content 追加了新步骤 ----
    rows = await _rest(
        rest, "GET", "/api/templates",
        params={"type": "decomposition", "category": category_id},
    )
    current = next(row for row in rows if row["id"] == template_id)
    assert current["version"] == initial_version + 1, (
        f"模板版本应 +1（{initial_version}→{initial_version + 1}），实际 {current['version']}"
    )
    steps = current["content"]["common_steps"]
    assert steps == BASE_STEPS + [USER_ADDED_STEP], f"common_steps 应在末尾追加新步骤，实际 {steps}"
    assert current["content"]["estimated_subtask_count"] == len(BASE_STEPS), "未指示调整的字段应保持不变"
    print(f"[e2e] 模板 version={current['version']}，common_steps={steps}")

    # ---- 阶段 9：版本行 change_summary 含 reason（10.1 核心断言） ----
    versions = await _rest(rest, "GET", f"/api/templates/{template_id}/versions")
    assert len(versions) == 2, f"应有 2 条版本快照（创建 + 自动学习），实际 {len(versions)}"
    latest, first = versions[0], versions[1]
    assert latest["version"] == initial_version + 1
    assert first["version"] == initial_version
    assert first["change_summary"] == "创建模板", f"版本 1 摘要应为「创建模板」，实际 {first['change_summary']}"
    change_summary = latest["change_summary"] or ""
    assert change_summary.startswith(_AUTO_LEARN_PREFIX), (
        f"自动学习摘要应以「{_AUTO_LEARN_PREFIX}」开头，实际「{change_summary}」"
    )
    assert USER_ADDED_STEP in change_summary, f"摘要应含新增步骤名，实际「{change_summary}」"
    assert LEARN_REASON in change_summary, f"摘要应含 reason，实际「{change_summary}」"
    assert latest["parent_version_id"] == first["id"], "版本 2 应链接到版本 1 的快照行"
    assert latest["content"]["common_steps"] == BASE_STEPS + [USER_ADDED_STEP]
    print(f"[e2e] 版本 1 change_summary={first['change_summary']}")
    print(f"[e2e] 版本 2 change_summary={change_summary}")

    # ---- 阶段 10：执行日志有一条 manual/success ----
    executions = await call_tool(
        "get_summary_executions", {"trigger_source": "manual", "limit": 10}
    )
    assert executions, "手动触发的执行日志应可查到"
    ours = next(
        (
            row
            for row in executions
            if row.get("status") == "success"
            and category_name in (row.get("categories_updated") or [])
        ),
        None,
    )
    assert ours is not None, (
        f"应有一条 manual/success 且 categories_updated 含 {category_name}，"
        f"实际最近 {len(executions)} 条：{[(r.get('status'), r.get('categories_updated')) for r in executions]}"
    )
    assert ours["trigger_source"] == "manual"
    assert ours["error_message"] is None
    print(f"[e2e] 执行日志：run_at={ours['run_at']} status={ours['status']} "
          f"trigger_source={ours['trigger_source']} categories_updated={ours['categories_updated']}")

    # ---- 阶段 11：桩 LLM 的调用序（证明确实走完了分类/模板匹配/拆解/学习四段） ----
    print(f"[e2e] 桩 LLM 调用序：{stub.calls}")
    assert stub.calls[0] == "classification"
    assert "template_match" in stub.calls, "分类下有启用模板时应发生模板匹配调用"
    assert "decomposition" in stub.calls
    assert stub.calls.count("summary") >= 1, "总结学习应至少发生一次 LLM 调用"
