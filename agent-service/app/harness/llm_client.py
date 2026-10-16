"""LLM 流式客户端；公众号会话使用独立且有上限的超时、输出和重试预算。"""
import asyncio
from time import monotonic
from typing import Any

import httpx
from loguru import logger
from openai import AsyncOpenAI

from ..settings import get_settings
from .json_guard import AIJsonGuardError


class LLMClient:
    """兼容既有拆解调用，并为公众号会话提供独立参数。"""

    def __init__(self, *, conversation: bool = False) -> None:
        settings = get_settings()
        self._model = settings.deepseek_model
        self._conversation = conversation
        self._attempts = settings.conversation_llm_max_attempts if conversation else 3
        self._thinking = settings.conversation_llm_thinking if conversation else "omit"
        self._max_tokens = settings.conversation_llm_max_tokens if conversation else None
        client_kwargs: dict[str, Any] = {}
        if conversation:
            client_kwargs.update(
                timeout=httpx.Timeout(
                    settings.conversation_llm_read_timeout_seconds,
                    connect=settings.conversation_llm_connect_timeout_seconds,
                ),
                max_retries=settings.conversation_llm_sdk_max_retries,
            )
        self._client = AsyncOpenAI(
            api_key=settings.deepseek_api_key.get_secret_value(),
            base_url=settings.deepseek_base_url,
            **client_kwargs,
        )

    async def stream_chat(
        self,
        messages: list[dict[str, Any]],
        on_token: Any = None,
        *,
        response_format: dict[str, str] | None = None,
    ) -> str:
        """重试次数受调用配置约束，取消请求时关闭上游流。"""
        for attempt in range(self._attempts):
            try:
                return await self._stream_once(messages, on_token, response_format)
            except AIJsonGuardError:
                # 截断和格式错误不能靠重复生成同一请求恢复。
                raise
            except Exception:
                if attempt + 1 >= self._attempts:
                    raise
                await asyncio.sleep(float(2 ** attempt))
        raise RuntimeError("LLM 调用次数配置无效")

    async def _stream_once(
        self,
        messages: list[dict[str, Any]],
        on_token: Any,
        response_format: dict[str, str] | None,
    ) -> str:
        kwargs: dict[str, Any] = {"model": self._model, "messages": messages, "stream": True}
        if response_format is not None:
            kwargs["response_format"] = response_format
        if self._thinking != "omit":
            kwargs["extra_body"] = {"thinking": {"type": self._thinking}}
        if self._max_tokens is not None:
            kwargs["max_tokens"] = self._max_tokens
        started = monotonic()
        first_content_ms = None
        chunks: list[str] = []
        stream = None
        outcome = "error"
        try:
            stream = await self._client.chat.completions.create(**kwargs)
            async for chunk in stream:
                if not chunk.choices:
                    continue
                choice = chunk.choices[0]
                if getattr(choice, "finish_reason", None) == "length":
                    raise AIJsonGuardError(56002, "整理内容超过长度限制，请把长消息分段发送")
                delta = choice.delta.content
                if delta:
                    if first_content_ms is None:
                        first_content_ms = round((monotonic() - started) * 1000)
                    chunks.append(delta)
                    if on_token is not None:
                        on_token(delta)
            outcome = "completed"
            return "".join(chunks)
        except asyncio.CancelledError:
            outcome = "cancelled"
            raise
        finally:
            if stream is not None:
                close = getattr(stream, "close", None) or getattr(stream, "aclose", None)
                if close is not None:
                    await close()
            if self._conversation:
                logger.info(
                    "conversation_llm outcome={} elapsed_ms={} first_content_ms={} content_chars={}",
                    outcome, round((monotonic() - started) * 1000),
                    first_content_ms, sum(map(len, chunks)),
                )
