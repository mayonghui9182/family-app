"""杂项补洞：SSE 节流器 / 错误默认码 / 路由参数校验 / 日志。（每日总结调度见 test_summary_job.py）"""
import pytest

from app.harness.sse_stream import SSEStreamWriter


def test_writer_interval_gating_and_drain() -> None:
    import time as _time
    from unittest.mock import patch

    writer = SSEStreamWriter(reduced_motion=False)
    writer.feed("a")
    assert writer.take_frames() == []  # 未到 100ms 不出帧
    with patch("app.harness.sse_stream.time.monotonic", return_value=writer._last_take + 0.2):
        frames = writer.take_frames()
    assert frames and '"delta":"a"' in frames[0]  # noqa: SLF001
    writer.feed("b")
    writer.feed("c")
    drained = writer.drain()
    assert '"delta":"bc"' in drained[0]  # noqa: SLF001
    assert writer.drain() == [] and writer.pending is False
    assert SSEStreamWriter(reduced_motion=True).interval == 3.0
    del _time


def test_error_default_codes_and_messages() -> None:
    from app.models.errors import (
        AuthError,
        BackendWriteError,
        FamilyScopeError,
        InternalSecretError,
        LLMTimeoutError,
        ParamError,
        RoleDeniedError,
        SummaryAlreadyRanError,
    )

    pairs = [
        (ParamError(), 40099, "参数校验失败"),
        (InternalSecretError(), 40102, "内部秘钥不正确"),
        (AuthError(), 40103, "登录已过期"),
        (RoleDeniedError(), 40302, "孩子"),
        (FamilyScopeError(), 46000, "家庭"),
        (LLMTimeoutError(), 56001, "超时"),
        (BackendWriteError(), 56003, "保存失败"),
        (SummaryAlreadyRanError(), 46502, "force=true"),
    ]
    for err, code, frag in pairs:
        assert err.code == code and frag in err.message
    assert ParamError("自定义").message == "自定义"
    assert RoleDeniedError("只有家庭管理员或成人成员可手动触发总结").message.endswith("手动触发总结")


async def test_routes_param_validation_and_singleton(monkeypatch: pytest.MonkeyPatch) -> None:
    import httpx

    from app.main import create_app
    from app.routes import chat_routes
    from app.settings import get_settings
    from tests.conftest import CTX_FULL, VALID_DECOMPOSE_JSON, FakeLLM, make_decomposer

    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},
        {"kind": "json", "text": VALID_DECOMPOSE_JSON},
    ])
    dec, _be = make_decomposer(llm, CTX_FULL)
    app = create_app()
    app.dependency_overrides[chat_routes.get_decomposer] = lambda: dec
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
        no_family = await client.post("/api/v1/chat/stream", json={"message": "x"},
                                      headers={"X-Access-Token": "t"})
        bad_family = await client.post("/api/v1/chat/stream", json={"message": "x"},
                                       headers={"X-Family-Id": "abc", "X-Access-Token": "t"})
        zero_family = await client.post("/api/v1/chat/stream", json={"message": "x"},
                                        headers={"X-Family-Id": "0", "X-Access-Token": "t"})
        no_token = await client.post("/api/v1/chat/stream", json={"message": "x"},
                                     headers={"X-Family-Id": "1"})
        a2 = await client.post("/api/v1/chat", json={"message": "周末大扫除"},
                               headers={"X-Family-Id": "1", "X-Access-Token": "t"})
        stub = await client.post("/api/internal/summary/one-family",
                                 json={"family_id": 1, "category_ids": []},
                                 headers={"X-Internal-Secret": ""})
    for resp in (no_family, bad_family, zero_family):
        assert resp.status_code == 400 and resp.json()["code"] == 40099
    assert no_token.json()["code"] == 40103  # 缺 token 视为未登录
    assert a2.json()["code"] == 0 and a2.json()["data"]["task_id"] == 501
    assert stub.status_code == 401  # 空秘钥被拒（未配置防线）
    # 单例工厂可构建真实编排器（不联网）：openai SDK 构造需非空 key，注入占位
    monkeypatch.setenv("DEEPSEEK_API_KEY", "sk-test-placeholder")
    get_settings.cache_clear()
    try:
        real = chat_routes.get_decomposer()
        assert real is chat_routes.get_decomposer()
    finally:
        chat_routes._singleton = None  # noqa: SLF001 复位，防污染其他测试
        get_settings.cache_clear()


async def test_agent_notes_use_lowercase_target_type() -> None:
    """跨服务契约（回归防护）：AI 拆解写便签必须传后端认识的小写 'task'。"""
    from tests.conftest import CTX_FULL, VALID_DECOMPOSE_JSON, FakeLLM, make_decomposer

    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},
        {"kind": "json", "text": VALID_DECOMPOSE_JSON},
    ])
    dec, be = make_decomposer(llm, CTX_FULL)

    decomposed, task_id, _msg = await dec.run_buffered(
        family_id=1, title="周末大扫除", description="", category_hint=None
    )

    note_calls = [c for c in be.calls if c[0] == "add_note"]
    assert decomposed is True and task_id == 501
    assert note_calls, "LLM 输出含 notes，必须落便签"
    assert all(c[2]["target_type"] == "task" for c in note_calls), "大写 TASK 会被后端 C-8 拒 400"


def test_logging_setup_and_trace_filter() -> None:
    from app.utils.log import _inject_trace_id, setup_logging, trace_id_var

    record: dict = {"extra": {}}
    token = trace_id_var.set("T-1")
    assert _inject_trace_id(record) is True and record["extra"]["trace_id"] == "T-1"
    trace_id_var.reset(token)
    bound: dict = {"extra": {"trace_id": "B-2"}}
    _inject_trace_id(bound)
    assert bound["extra"]["trace_id"] == "B-2"  # bind 值优先，不被空 contextvar 覆盖
    setup_logging()  # 冒烟：可重复调用不抛


async def test_db_reader_no_engine_branches() -> None:
    from app.connectors.db_reader import DbReader

    reader = DbReader()  # 无 DB_AGENT_READER_URL → 引擎为 None
    assert reader._engine is None  # noqa: SLF001
    ctx = await reader.get_context(1)
    assert ctx == {"categories": [], "templates": [], "few_shot": [], "workload": {}}
    with pytest.raises(RuntimeError):  # 真机查询缺引擎时早失败，绝不静默返回空
        await reader.get_recent_completed(1, 3)
    with pytest.raises(RuntimeError):
        await reader.get_cancelled_agent_signal(1)
    with pytest.raises(TypeError):  # L2 隔离守卫：真机查询同样强制 int
        await reader.get_cancelled_agent_signal("1")  # type: ignore[arg-type]


def test_db_reader_respects_agent_reader_grant_matrix() -> None:
    """权限矩阵漂移守卫（D11）：agent_reader 列授权排除 tasks/categories.deleted_at，
    SQL 里出现该列 = 真机 42501。分类清单只能走后端 C-10，故 db_reader 不得再有 get_categories。
    """
    from app.connectors import db_reader

    assert not hasattr(db_reader.DbReader, "get_categories"), "分类必须走 C-10（软删过滤在后端）"
    constants = [v for k, v in vars(db_reader).items() if k.startswith("_SQL_")]
    assert len(constants) == 2, "db_reader 的真机 SQL 必须以模块级常量集中，便于权限矩阵审计"
    for sql in constants:
        assert "deleted_at" not in str(sql), f"SQL 引用了未授权列 deleted_at：{sql}"


async def test_decomposer_takes_category_list_from_backend_c10() -> None:
    """编排层证据：分类清单来自 C-10（哪怕 db_reader 返回空 categories 也能正常分类）。"""
    from tests.conftest import VALID_DECOMPOSE_JSON, FakeLLM, make_decomposer

    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},
        {"kind": "json", "text": VALID_DECOMPOSE_JSON},
    ])
    dec, be = make_decomposer(
        llm, {"categories": [], "templates": [], "few_shot": ["上周完成：厨房深度清洁"], "workload": {}}
    )
    be.categories = [{"id": 3, "name": "家务"}]

    decomposed, task_id, _msg = await dec.run_buffered(family_id=1, title="周末大扫除", description="")

    assert decomposed is True and task_id == 501
    assert "家务" in llm.calls[0]["messages"][1]["content"], "分类提示词必须用 C-10 的分类清单"


def test_db_reader_as_list_normalizes_pg_json_shapes() -> None:
    """asyncpg 回传 json_agg 可能是 None / 字符串 / 已解码 list，三态都要归一（否则样本静默丢失）。"""
    from app.connectors.db_reader import DbReader

    assert DbReader._as_list(None) == []  # noqa: SLF001
    assert DbReader._as_list('[{"title": "拖地"}]') == [{"title": "拖地"}]  # noqa: SLF001
    assert DbReader._as_list('["便签A", "便签B"]') == ["便签A", "便签B"]  # noqa: SLF001
    assert DbReader._as_list("非 JSON 文本") == []  # noqa: SLF001
    assert DbReader._as_list('{"不是数组": true}') == []  # noqa: SLF001
    assert DbReader._as_list([{"a": 1}]) == [{"a": 1}]  # noqa: SLF001


def test_secretstr_masked_in_repr_and_logs() -> None:
    """check_list 8：SecretStr 任何 repr/日志形态不落明文。"""
    from app.settings import Settings

    settings = Settings(deepseek_api_key="sk-supersecret", internal_api_secret="topsecret")
    assert "sk-supersecret" not in repr(settings)
    assert "topsecret" not in repr(settings)
    assert "sk-supersecret" not in repr(settings.deepseek_api_key)
    assert settings.deepseek_api_key.get_secret_value() == "sk-supersecret"  # 运行时仍可用
