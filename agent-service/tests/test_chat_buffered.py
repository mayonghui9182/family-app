"""A-2 缓冲端点（POST /api/v1/chat）的 session_id 透传契约。

Change 14 的护栏在这里：微信入站拆解走 A-2 且必带草稿 session_id，
一旦路由层把 session_id 漏给编排器，stream() 就按「session_id 为空」判定为
B-1 缓冲式，直接 create_task 写 tasks 表——用户在 H5 草稿页还没点确认，
任务就已经存在了，违反 design.md D5「确认前 tasks 零写入」。
"""
from typing import Any

import httpx
from fastapi import FastAPI

from app.main import create_app
from app.models.errors import AuthError
from app.routes import chat_routes
from tests.conftest import (
    CTX_FULL,
    VALID_DECOMPOSE_JSON,
    FakeBackend,
    FakeLLM,
    make_decomposer,
)

_AUTH_HEADERS = {"X-Family-Id": "1", "X-Access-Token": "tok"}


class _RecordingBackend(FakeBackend):
    """在 B-6 假件上补记 session_id：只有基类的 saved_proposal 无法证明参数真的透传到位。"""

    def __init__(self) -> None:
        super().__init__()
        self.proposal_calls: list[tuple[int, list[dict[str, Any]]]] = []

    async def save_proposal(self, session_id: int, proposed_tasks: list[dict[str, Any]],
                            **kwargs: Any) -> None:
        self.proposal_calls.append((session_id, list(proposed_tasks)))
        await super().save_proposal(session_id, proposed_tasks, **kwargs)


def _llm() -> FakeLLM:
    """两次 LLM 调用：Step 3 分类 + Step 5 拆解。"""
    return FakeLLM(
        [
            {"kind": "json", "text": '{"category_id": 3}'},
            {"kind": "json", "tokens": ["a", "b"], "text": VALID_DECOMPOSE_JSON},
        ]
    )


def _build_app(llm: FakeLLM, backend: FakeBackend, auth_error: Exception | None = None) -> FastAPI:
    dec, _ = make_decomposer(llm, CTX_FULL, backend=backend, auth_error=auth_error)
    application = create_app()
    application.dependency_overrides[chat_routes.get_decomposer] = lambda: dec
    return application


async def _post(application: FastAPI, payload: dict[str, Any]) -> httpx.Response:
    transport = httpx.ASGITransport(app=application)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as client:
        return await client.post("/api/v1/chat", json=payload, headers=_AUTH_HEADERS)


async def test_buffered_with_session_id_stores_proposal_and_writes_no_task() -> None:
    """带 session_id → 草稿模式：proposal 存进 session，业务表一行不写，task_id 返回 null。"""
    backend = _RecordingBackend()
    resp = await _post(_build_app(_llm(), backend), {"message": "周六带爸去复查", "session_id": 7})

    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    assert body["data"]["task_id"] is None, "草稿模式绝不能返回真实 task_id"
    assert [s["title"] for s in body["data"]["subtasks"]] == ["擦窗", "拖地", "整理阳台"]
    assert [c[0] for c in backend.proposal_calls] == [7], "session_id 必须原样透传到 B-6"
    assert len(backend.proposal_calls[0][1]) == 3
    assert backend.calls == [], "草稿模式下 create_task/create_subtasks_batch/add_note 都不得被调用"


async def test_buffered_without_session_id_keeps_b1_direct_write() -> None:
    """回归护栏：不带 session_id 时 A-2 仍按 B-1 直写业务表，现有行为逐字不变。"""
    backend = _RecordingBackend()
    resp = await _post(_build_app(_llm(), backend), {"message": "周末大扫除"})

    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 0
    assert body["data"]["task_id"] == 501
    assert backend.proposal_calls == [], "未传 session_id 时不得误走 B-6"
    assert [c[0] for c in backend.calls] == ["create_task", "create_subtasks_batch", "add_note"]


async def test_buffered_auth_failure_short_circuits_llm() -> None:
    """身份校验失败 → 不调 LLM、不建草稿、返回 40103 中文提示。"""
    llm = FakeLLM([])
    backend = _RecordingBackend()
    resp = await _post(
        _build_app(llm, backend, auth_error=AuthError()),
        {"message": "周六带爸去复查", "session_id": 7},
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["code"] == 40103
    assert "data" not in body
    assert llm.calls == []
    assert backend.calls == [] and backend.proposal_calls == []
