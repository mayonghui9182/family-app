"""LLM 输出 JSON 解析容错（tasks.md 7.2，spec ai-decomposition）。

三级策略：
1. 直接 `json.loads`；
2. 失败则用正则提取首个平衡的 `{...}` 块再 parse 一次
   （json_object 模式下输出应为单个对象，取首个平衡块足够）；
3. 仍失败抛 `LLMOutputUnparsable`，由调用方记日志并降级。
"""

from __future__ import annotations

import json
from typing import Any

from src.llm.exceptions import LLMOutputUnparsable


def _extract_first_brace_block(text: str) -> str | None:
    """提取首个花括号平衡的 `{...}` 块；字符串内的花括号不计入配对。"""
    start = text.find("{")
    if start < 0:
        return None
    depth = 0
    in_string = False
    escaped = False
    for index in range(start, len(text)):
        char = text[index]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    return None


def parse_llm_json(raw: str) -> Any:
    """解析 LLM 输出为 JSON，容错前后缀文本；彻底失败抛 `LLMOutputUnparsable`。"""
    text = (raw or "").strip()
    try:
        return json.loads(text)
    except (json.JSONDecodeError, ValueError):
        pass
    block = _extract_first_brace_block(text)
    if block is not None:
        try:
            return json.loads(block)
        except (json.JSONDecodeError, ValueError):
            pass
    raise LLMOutputUnparsable(raw or "")
