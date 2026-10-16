"""llm_client：流式回调 + 指数退避重试（假件注入，零网络）。"""
from collections.abc import AsyncIterator
from types import SimpleNamespace
from typing import Any

import pytest

from app.harness.llm_client import LLMClient


def make_llm(create: Any) -> LLMClient:
    lc = LLMClient.__new__(LLMClient)  # noqa: SLF001
    lc._model = "deepseek-chat"  # noqa: SLF001
    lc._attempts = 3
    lc._thinking = "omit"
    lc._max_tokens = None
    lc._conversation = False
    lc._client = SimpleNamespace(  # noqa: SLF001
        chat=SimpleNamespace(completions=SimpleNamespace(create=create))
    )
    return lc


def chunk(text: str) -> Any:
    return SimpleNamespace(choices=[SimpleNamespace(delta=SimpleNamespace(content=text))])


async def aiter_chunks(items: list[Any]) -> AsyncIterator[Any]:
    for item in items:
        yield item


async def test_stream_chat_collects_text_and_calls_on_token() -> None:
    captured: dict[str, Any] = {}

    async def create(**kwargs: Any) -> Any:
        captured.update(kwargs)
        return aiter_chunks([chunk("你"), chunk("好")])

    tokens: list[str] = []
    llm = make_llm(create)
    out = await llm.stream_chat([{"role": "user", "content": "hi"}], on_token=tokens.append,
                                response_format={"type": "json_object"})
    assert out == "你好" and tokens == ["你", "好"]
    assert captured["model"] == "deepseek-chat" and captured["stream"] is True
    assert captured["response_format"] == {"type": "json_object"}  # L1 硬约束下发


async def test_retry_backoff_then_success(monkeypatch: pytest.MonkeyPatch) -> None:
    sleeps: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        sleeps.append(seconds)

    monkeypatch.setattr("app.harness.llm_client.asyncio.sleep", fake_sleep)
    attempts = {"n": 0}

    async def create(**kwargs: Any) -> Any:
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise RuntimeError("transient")
        return aiter_chunks([chunk("ok")])

    llm = make_llm(create)
    out = await llm.stream_chat([])
    assert out == "ok" and attempts["n"] == 2 and sleeps == [1.0]  # 首次退避 1s


async def test_retry_exhausted_raises_last_error(monkeypatch: pytest.MonkeyPatch) -> None:
    async def fake_sleep(seconds: float) -> None:
        pass

    monkeypatch.setattr("app.harness.llm_client.asyncio.sleep", fake_sleep)

    async def create(**kwargs: Any) -> Any:
        raise ValueError("always down")

    llm = make_llm(create)
    with pytest.raises(ValueError):
        await llm.stream_chat([])


async def test_conversation_budget_sends_thinking_and_output_limit_without_hidden_retries() -> None:
    captured: dict[str, Any] = {}
    calls = 0

    async def create(**kwargs: Any) -> Any:
        nonlocal calls
        calls += 1
        captured.update(kwargs)
        return aiter_chunks([chunk('{"ok":true}')])

    llm = make_llm(create)
    llm._conversation = True
    llm._attempts = 1
    llm._thinking = "disabled"
    llm._max_tokens = 4096
    assert await llm.stream_chat([]) == '{"ok":true}'
    assert calls == 1
    assert captured["extra_body"] == {"thinking": {"type": "disabled"}}
    assert captured["max_tokens"] == 4096


async def test_truncated_stream_is_closed_and_not_retried() -> None:
    from app.harness.json_guard import AIJsonGuardError

    calls = 0
    closed = False

    async def incomplete() -> AsyncIterator[Any]:
        nonlocal closed
        try:
            yield SimpleNamespace(choices=[
                SimpleNamespace(delta=SimpleNamespace(content='{"action":'), finish_reason="length")
            ])
        finally:
            closed = True

    async def create(**kwargs: Any) -> Any:
        nonlocal calls
        calls += 1
        return incomplete()

    llm = make_llm(create)
    with pytest.raises(AIJsonGuardError, match="长度"):
        await llm.stream_chat([])
    assert calls == 1
    assert closed


def test_conversation_constructor_uses_independent_sdk_budget(monkeypatch: pytest.MonkeyPatch) -> None:
    captured: dict[str, Any] = {}

    def factory(**kwargs: Any) -> Any:
        captured.update(kwargs)
        return SimpleNamespace()

    monkeypatch.setattr("app.harness.llm_client.AsyncOpenAI", factory)
    LLMClient(conversation=True)
    assert captured["max_retries"] == 0
    assert captured["timeout"].connect == 8
    assert captured["timeout"].read == 25
