"""PROFILE_INTERVIEW 档案访谈单轮流测试（change 2026-09-26-profile-interview）。

覆盖：正常轮事件序 + 白名单过滤 + B-5/B-6/B-7 调用；done=true 收尾标题；
缺目标档案 56003；B-5 失败转 error 帧且不推 proposal。
"""
import json

import httpx

from tests.conftest import CTX_COLD, FakeBackend, FakeLLM, collect, make_decomposer

INTERVIEW_JSON = json.dumps(
    {
        "reply": "爸爸平时怎么上班呀？",
        "patch": {"blood_type": "A", "commute_mode": "开车", "hack_field": "x"},
        "remove_keys": ["common_meds", "not_in_schema"],
        "profile_id": 12,
        "done": False,
    },
    ensure_ascii=False,
)

INTERVIEW_DONE_JSON = json.dumps(
    {
        "reply": "都记好啦，点确认保存吧。",
        "patch": {"blood_type": "A", "commute_mode": "开车"},
        "remove_keys": [],
        "profile_id": 12,
        "done": True,
    },
    ensure_ascii=False,
)


def _adult_backend(session_context: dict) -> FakeBackend:
    """构造带 ADULT 目标档案（id=12）与会话上下文的假后端。"""
    be = FakeBackend()
    be.profiles = [
        {"id": 12, "name": "爸爸", "relation": "爸爸", "profileType": "ADULT",
         "profileData": {"blood_type": "A"}},
    ]
    be.session_context = session_context
    return be


async def test_interview_round_event_order_and_persist() -> None:
    """正常轮：message → patch_preview → proposal → done；白名单过滤越界键；B-5/B-6/B-7 均调用。"""
    llm = FakeLLM([{"kind": "raw", "text": INTERVIEW_JSON}])
    be = _adult_backend({
        "session_type": "PROFILE_INTERVIEW", "assignee_profile_id": 12,
        "messages": [{"role": "user", "content": "开始吧"}], "revision": "2026-09-26T00:00:00Z",
    })
    dec, _ = make_decomposer(llm, CTX_COLD, backend=be)
    frames = await collect(dec.stream(family_id=1, title="我开车上班", session_id=99))

    assert [f["event"] for f in frames] == ["message", "patch_preview", "proposal", "done"]
    assert frames[0]["data"] == {"role": "assistant", "content": "爸爸平时怎么上班呀？"}
    # 白名单过滤：ADULT 保留 blood_type/commute_mode，剔除 hack_field；remove_keys 剔除 not_in_schema
    assert frames[1]["data"]["profileId"] == 12
    assert frames[1]["data"]["patch"] == {"blood_type": "A", "commute_mode": "开车"}
    assert frames[1]["data"]["remove_keys"] == ["common_meds"]
    # 未完成 → 占位标题；proposal 带 profilePatch 供 D-6 saveOnlyProfile 判定
    assert frames[2]["data"]["title"] == "继续完善「爸爸」的档案"
    assert frames[2]["data"]["profilePatch"] == {"blood_type": "A", "commute_mode": "开车"}
    assert frames[2]["data"]["interview_done"] is False
    assert frames[3]["data"] == {"task_id": None, "decomposed": False}

    # B-5 覆盖式补丁（扁平行格式：字段展开在顶层）、B-6 提案、B-7 对话（user 在前 assistant 在后）
    assert be.saved_patch == {
        "profile_id": 12,
        "blood_type": "A",
        "commute_mode": "开车",
        "remove_keys": ["common_meds"],
    }
    assert be.saved_proposal and be.saved_proposal[0]["assignee_profile_id"] == 12
    assert be.appended_messages == [("user", "我开车上班"), ("assistant", "爸爸平时怎么上班呀？")]


async def test_interview_done_closing_title() -> None:
    """done=true：收尾标题含「档案已完善」，done 帧 decomposed=True。"""
    llm = FakeLLM([{"kind": "raw", "text": INTERVIEW_DONE_JSON}])
    be = _adult_backend({
        "session_type": "PROFILE_INTERVIEW", "assignee_profile_id": 12, "messages": [],
    })
    dec, _ = make_decomposer(llm, CTX_COLD, backend=be)
    frames = await collect(dec.stream(family_id=1, title="就这些", session_id=99))
    proposal = next(f for f in frames if f["event"] == "proposal")
    assert "档案已完善：爸爸" in proposal["data"]["title"]
    assert proposal["data"]["interview_done"] is True
    assert frames[-1]["data"] == {"task_id": None, "decomposed": True}


async def test_interview_missing_target_profile_maps_56003() -> None:
    """会话缺 assignee_profile_id → error 帧 56003，不触达 LLM 之外的写入。"""
    llm = FakeLLM([{"kind": "raw", "text": INTERVIEW_JSON}])
    be = _adult_backend({"session_type": "PROFILE_INTERVIEW", "messages": []})
    dec, _ = make_decomposer(llm, CTX_COLD, backend=be)
    frames = await collect(dec.stream(family_id=1, title="开始", session_id=99))
    assert frames[-1]["event"] == "error"
    assert frames[-1]["data"]["code"] == 56003
    assert "目标档案" in frames[-1]["data"]["msg"]
    assert be.saved_patch is None


async def test_interview_patch_persist_failure_no_proposal() -> None:
    """B-5 写补丁失败 → error 帧，且不推 proposal（避免确认后无补丁可合并的假成功）。"""

    class ExplodingPatchBackend(FakeBackend):
        async def save_extracted_patch(self, session_id, extracted_patch, *, family_id):
            raise httpx.HTTPError("backend down")

    llm = FakeLLM([{"kind": "raw", "text": INTERVIEW_JSON}])
    be = ExplodingPatchBackend()
    be.profiles = [{"id": 12, "name": "爸爸", "profileType": "ADULT", "profileData": {}}]
    be.session_context = {
        "session_type": "PROFILE_INTERVIEW", "assignee_profile_id": 12, "messages": [],
    }
    dec, _ = make_decomposer(llm, CTX_COLD, backend=be)
    frames = await collect(dec.stream(family_id=1, title="我开车上班", session_id=99))
    events = [f["event"] for f in frames]
    assert "proposal" not in events
    assert frames[-1]["event"] == "error"
