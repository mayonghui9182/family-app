"""LLM 适配层：DeepSeek OpenAI 兼容调用（tasks.md 7.2，design.md D6）。

约定：
- 统一 `response_format={"type": "json_object"}` 强制 JSON 输出（spec 固化）
- 超时参数可注入（拆解/总结各自的超时预算不同）
- 传输级失败统一抛 `LLMCallFailed`；输出解析失败抛 `LLMOutputUnparsable`
- 每次调用记结构化日志：purpose（用途）、prompt 摘要、耗时、token 数
- 测试可注入桩 client（duck typing：提供 `chat.completions.create`）
"""

from __future__ import annotations

import logging
import time
from typing import Any, Protocol

from openai import AsyncOpenAI

from src.config import Settings, settings as default_settings
from src.llm.exceptions import LLMCallFailed
from src.llm.json_parse import parse_llm_json

logger = logging.getLogger("agent_service.llm")

# prompt 摘要长度：够定位问题，又不至于把整段 prompt 打进日志
_PROMPT_SUMMARY_LIMIT = 200


class _ChatCompletions(Protocol):
    async def create(self, **kwargs: Any) -> Any: ...


class _CompletionsHolder(Protocol):
    chat: Any


def _summarize(text: str) -> str:
    """prompt 摘要：截断到固定长度，避免日志膨胀。"""
    compact = " ".join((text or "").split())
    return compact[:_PROMPT_SUMMARY_LIMIT]


class LLMClient:
    """单一 LLM 适配点：chat.completions + JSON 输出 + 解析容错。"""

    def __init__(
        self,
        settings: Settings | None = None,
        *,
        transport: Any = None,
    ) -> None:
        cfg = settings or default_settings
        self._settings = cfg
        if transport is not None:
            # 测试桩：直接注入 AsyncOpenAI 替身
            self._client = transport
        else:
            if not cfg.llm_api_key:
                # 不在启动期崩溃，调用处才报错（config.py 约定）
                raise LLMCallFailed("LLM_API_KEY 未配置，无法调用 LLM")
            self._client = AsyncOpenAI(
                api_key=cfg.llm_api_key,
                base_url=cfg.llm_base_url,
                timeout=cfg.llm_timeout_seconds,
            )

    async def chat_json(
        self,
        *,
        system: str,
        user: str,
        purpose: str,
        timeout_seconds: float | None = None,
    ) -> dict[str, Any]:
        """一次 JSON 对话调用，返回解析后的 dict。

        - `purpose`：用途标记（分类/模板匹配/拆解/总结），进日志
        - `timeout_seconds`：本次调用超时，缺省用配置值
        """
        started = time.perf_counter()
        try:
            response = await self._client.chat.completions.create(
                model=self._settings.llm_model,
                messages=[
                    {"role": "system", "content": system},
                    {"role": "user", "content": user},
                ],
                response_format={"type": "json_object"},
                timeout=timeout_seconds or self._settings.llm_timeout_seconds,
            )
        except LLMCallFailed:
            raise
        except Exception as exc:  # 传输级失败统一收敛为领域异常
            elapsed_ms = (time.perf_counter() - started) * 1000
            logger.warning(
                "LLM 调用失败 purpose=%s elapsed_ms=%.1f error=%s",
                purpose,
                elapsed_ms,
                exc,
                extra={
                    "purpose": purpose,
                    "elapsed_ms": round(elapsed_ms, 1),
                    "error": str(exc),
                },
            )
            raise LLMCallFailed(f"LLM 调用失败：{exc}") from exc

        elapsed_ms = (time.perf_counter() - started) * 1000
        content = response.choices[0].message.content or ""
        usage = getattr(response, "usage", None)
        prompt_tokens = getattr(usage, "prompt_tokens", None) if usage else None
        completion_tokens = (
            getattr(usage, "completion_tokens", None) if usage else None
        )
        logger.info(
            "LLM 调用 purpose=%s elapsed_ms=%.1f prompt_tokens=%s completion_tokens=%s",
            purpose,
            elapsed_ms,
            prompt_tokens,
            completion_tokens,
            extra={
                "purpose": purpose,
                "prompt_summary": _summarize(system + "\n" + user),
                "elapsed_ms": round(elapsed_ms, 1),
                "prompt_tokens": prompt_tokens,
                "completion_tokens": completion_tokens,
            },
        )
        return parse_llm_json(content)
