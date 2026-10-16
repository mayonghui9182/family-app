"""LLM 适配层与 prompt 模板包。

对外只暴露领域方法（分类、模板匹配、拆解、总结），
统一使用 `response_format={"type": "json_object"}` 强制 JSON 输出，
并提供解析容错：直接 parse → 正则提取首个 `{...}` 重试一次 → 仍失败抛 `LLMOutputUnparsable`。
"""

from src.llm.client import LLMClient
from src.llm.exceptions import LLMCallFailed, LLMOutputUnparsable
from src.llm.json_parse import parse_llm_json

__all__ = ["LLMClient", "LLMCallFailed", "LLMOutputUnparsable", "parse_llm_json"]
