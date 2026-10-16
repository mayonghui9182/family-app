"""七步编排 4 场景（Task 2）：正常流 / 冷启动 / 超时 56001 / 畸形 JSON 56002。"""
from tests.conftest import CTX_COLD, CTX_FULL, VALID_DECOMPOSE_JSON, FakeBackend, FakeLLM, collect, make_decomposer


async def test_normal_flow_event_order_and_persist() -> None:
    """正常流：classification → reasoning×N → proposal → done；写后端一次、无回滚。"""
    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},
        {"kind": "json", "tokens": ["拆解", "中"], "text": VALID_DECOMPOSE_JSON},
    ])
    dec, be = make_decomposer(llm, CTX_FULL)
    frames = await collect(dec.stream(family_id=1, title="周末大扫除", access_token="tok"))
    assert [f["event"] for f in frames] == ["classification", "reasoning", "proposal", "done"]
    assert frames[0]["data"] == {"category": "家务"}
    assert frames[2]["data"]["task_id"] == 501
    assert len(frames[2]["data"]["subtasks"]) == 3
    assert frames[3]["data"] == {"task_id": 501, "decomposed": True}
    ops = [c[0] for c in be.calls]
    assert ops.count("create_task") == 1
    assert "cancel_task" not in ops
    # 写操作签名带 family_id 且 payload source=AGENT
    create = next(c for c in be.calls if c[0] == "create_task")
    assert create[1] == 1 and create[2]["source"] == "AGENT"


async def test_cold_start_prefix_and_no_categories() -> None:
    """冷启动（无历史/无分类）：首帧为 📌 无历史参考 reasoning 前缀，不产生 classification 帧。"""
    llm = FakeLLM([{"kind": "json", "tokens": ["通", "用"], "text": VALID_DECOMPOSE_JSON}])
    dec, be = make_decomposer(llm, CTX_COLD)
    be.categories = []  # 分类清单改由 C-10 提供（D11），无分类才不产 classification 帧
    frames = await collect(dec.stream(family_id=2, title="大扫除"))
    assert frames[0]["event"] == "reasoning"
    assert frames[0]["data"]["delta"].startswith("📌 无历史参考")
    assert all(f["event"] != "classification" for f in frames)
    assert frames[-1]["event"] == "done"
    assert be.calls and be.calls[0][0] == "create_task"


async def test_llm_timeout_maps_56001(monkeypatch: object) -> None:
    """超时 → error 帧 56001；绝不触达后端写入。"""
    import os

    os.environ["MCP_TIMEOUT"] = "1"
    from app.settings import get_settings

    get_settings.cache_clear()
    llm = FakeLLM([
        {"kind": "slow", "seconds": 2},
        {"kind": "slow", "seconds": 2},
    ])
    dec, be = make_decomposer(llm, CTX_COLD)
    try:
        frames = await collect(dec.stream(family_id=1, title="大扫除"))
    finally:
        get_settings.cache_clear()
        os.environ.pop("MCP_TIMEOUT")
    assert frames[-1]["event"] == "error"
    assert frames[-1]["data"]["code"] == 56001
    assert not be.calls


async def test_malformed_json_maps_56002_no_backend_call() -> None:
    """畸形 JSON → error 帧 56002（L2 拦截）；create_task 不被调用。"""
    llm = FakeLLM([
        {"kind": "json", "text": '{"category_id": 3}'},
        {"kind": "raw", "text": "抱歉这不是JSON"},
    ])
    dec, be = make_decomposer(llm, CTX_FULL)
    frames = await collect(dec.stream(family_id=1, title="周末大扫除", access_token="tok"))
    assert frames[-1]["event"] == "error"
    assert frames[-1]["data"]["code"] == 56002
    assert "解析失败" in frames[-1]["data"]["msg"]
    assert not be.calls


async def test_b1_buffered_success_and_failure() -> None:
    """B-1 缓冲式：成功 (True, task_id)；失败 (False, None, 中文原因)。"""
    llm_ok = FakeLLM([{"kind": "raw", "text": VALID_DECOMPOSE_JSON}])
    dec, be_ok = make_decomposer(llm_ok, CTX_COLD)
    be_ok.categories = []  # C-10 无分类 → 单次 LLM 调用即出提案
    decomposed, task_id, msg = await dec.run_buffered(family_id=9, title="大扫除")
    assert decomposed is True and task_id == 501 and msg is None

    llm_bad = FakeLLM([{"kind": "raw", "text": "not-json"}])
    dec2, be_bad = make_decomposer(llm_bad, CTX_COLD)
    be_bad.categories = []
    decomposed2, task_id2, msg2 = await dec2.run_buffered(family_id=9, title="大扫除")
    assert decomposed2 is False and task_id2 is None and msg2


async def test_persist_failure_rollback_cancel_called_once() -> None:
    """check_list 7：子待批写入失败 → 已建任务软取消回滚恰 1 次 → error 56003。"""

    class ExplodingBatchBackend(FakeBackend):
        async def create_subtasks_batch(self, family_id, task_id, subtasks):
            raise RuntimeError("db down")

    llm = FakeLLM([{"kind": "raw", "text": VALID_DECOMPOSE_JSON}])
    be = ExplodingBatchBackend()
    dec, _ = make_decomposer(llm, CTX_COLD, backend=be)
    be.categories = []  # C-10 无分类 → 只调一次拆解 LLM
    sink: dict = {}
    frames = await collect(dec.stream(family_id=1, title="大扫除", result_sink=sink))
    assert frames[-1]["event"] == "error"
    assert frames[-1]["data"]["code"] == 56003
    ops = [c[0] for c in be.calls]
    assert ops.count("create_task") == 1
    assert ops.count("cancel_task") == 1  # 回滚恰一次
    assert "task_id" not in sink  # 失败不泄露 task_id
    # B-1 语义：写入失败 → decomposed=false，backend 走降级直建
    llm2 = FakeLLM([{"kind": "raw", "text": VALID_DECOMPOSE_JSON}])
    be2 = ExplodingBatchBackend()
    dec2, _ = make_decomposer(llm2, CTX_COLD, backend=be2)
    be2.categories = []
    ok, tid, msg = await dec2.run_buffered(family_id=1, title="大扫除")
    assert ok is False and tid is None and "保存失败" in str(msg)
