"""LLM 适配层测试（tasks.md 7.2）：JSON 解析容错 + 桩响应调用链。

覆盖 spec `LLM JSON output is enforced and parsed tolerantly` 的三种输入：
正常 JSON、带前后缀文本（正则提取恢复）、完全不可解析（抛领域异常）。
"""

from __future__ import annotations

import logging
from types import SimpleNamespace

import pytest

from src.config import Settings
from src.llm import LLMCallFailed, LLMClient, LLMOutputUnparsable, parse_llm_json


# ---------- 纯解析函数：三级容错 ----------


def test_parse_plain_json_object() -> None:
    """正常 JSON：直接 parse 成功。"""
    assert parse_llm_json('{"a": 1}') == {"a": 1}


def test_parse_json_with_surrounding_prose() -> None:
    """带前后缀文本：正则提取首个平衡 `{...}` 块后成功。"""
    raw = '好的，以下是结果：\n{"category": "市场推广", "confidence": 0.9}\n希望有帮助！'
    assert parse_llm_json(raw) == {"category": "市场推广", "confidence": 0.9}


def test_parse_nested_json_block() -> None:
    """嵌套对象：平衡块提取覆盖内层花括号。"""
    raw = '前缀 {"outer": {"inner": [1, 2]}} 后缀'
    assert parse_llm_json(raw) == {"outer": {"inner": [1, 2]}}


def test_parse_braces_inside_string_value() -> None:
    """字符串值内的花括号不干扰配对。"""
    raw = '说明 {"text": "含 {花括号} 的文本"} 尾巴'
    assert parse_llm_json(raw) == {"text": "含 {花括号} 的文本"}


def test_parse_unparsable_raises() -> None:
    """完全不可解析：抛 LLMOutputUnparsable 并保留原始输出。"""
    with pytest.raises(LLMOutputUnparsable) as exc_info:
        parse_llm_json("抱歉，我无法输出结构化内容。")
    assert exc_info.value.raw_output == "抱歉，我无法输出结构化内容。"


def test_parse_unbalanced_braces_raises() -> None:
    """花括号不平衡：提取失败，同样抛领域异常。"""
    with pytest.raises(LLMOutputUnparsable):
        parse_llm_json('开头 {"a": 1 没有闭合')


# ---------- LLMClient：桩 transport 调用链 ----------


def _make_stub(content: str) -> SimpleNamespace:
    """构造 AsyncOpenAI 替身：chat.completions.create 返回固定内容。"""

    async def _create(**kwargs):
        # 记录调用参数，供断言 response_format 等
        _create.last_kwargs = kwargs
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=content))],
            usage=SimpleNamespace(prompt_tokens=11, completion_tokens=7),
        )

    return SimpleNamespace(chat=SimpleNamespace(completions=SimpleNamespace(create=_create)))


def _make_client(transport: SimpleNamespace) -> tuple[LLMClient, SimpleNamespace]:
    cfg = Settings(llm_api_key="test-key")
    client = LLMClient(cfg, transport=transport)
    return client, transport


async def test_chat_json_returns_parsed_dict_and_forces_json_mode() -> None:
    """正常桩响应：返回解析后的 dict，且请求强制 json_object。"""
    stub = _make_stub('{"category": "工作汇报", "confidence": 0.8}')
    client, _ = _make_client(stub)

    result = await client.chat_json(system="系统", user="用户消息", purpose="分类")

    assert result == {"category": "工作汇报", "confidence": 0.8}
    kwargs = stub.chat.completions.create.last_kwargs
    assert kwargs["response_format"] == {"type": "json_object"}
    assert kwargs["model"] == "deepseek-chat"


async def test_chat_json_recovers_prose_wrapped_output() -> None:
    """带前后缀文本的桩响应：正则提取后正常返回。"""
    stub = _make_stub('思考如下 {"matched": false, "reason": "无历史参考"} 完毕')
    client, _ = _make_client(stub)

    result = await client.chat_json(system="s", user="u", purpose="模板匹配")

    assert result == {"matched": False, "reason": "无历史参考"}


async def test_chat_json_unparsable_raises_domain_exception() -> None:
    """完全不可解析的桩响应：抛 LLMOutputUnparsable。"""
    stub = _make_stub("我无法给出 JSON。")
    client, _ = _make_client(stub)

    with pytest.raises(LLMOutputUnparsable):
        await client.chat_json(system="s", user="u", purpose="拆解")


async def test_chat_json_logs_prompt_summary_elapsed_and_tokens(caplog) -> None:
    """结构化日志：purpose、prompt 摘要、耗时、token 数齐备。"""
    stub = _make_stub('{"ok": true}')
    client, _ = _make_client(stub)

    with caplog.at_level(logging.INFO, logger="agent_service.llm"):
        await client.chat_json(system="系统提示", user="用户输入", purpose="总结")

    records = [r for r in caplog.records if r.name == "agent_service.llm"]
    assert records, "缺少 LLM 调用日志"
    record = records[-1]
    assert getattr(record, "purpose", None) == "总结"
    assert "系统提示" in getattr(record, "prompt_summary", "")
    assert getattr(record, "prompt_tokens", None) == 11
    assert getattr(record, "completion_tokens", None) == 7
    assert getattr(record, "elapsed_ms", None) is not None


async def test_chat_json_transport_failure_raises_llm_call_failed() -> None:
    """传输级失败：统一抛 LLMCallFailed。"""

    async def _boom(**kwargs):
        raise TimeoutError("连接超时")

    transport = SimpleNamespace(
        chat=SimpleNamespace(completions=SimpleNamespace(create=_boom))
    )
    client, _ = _make_client(transport)

    with pytest.raises(LLMCallFailed):
        await client.chat_json(system="s", user="u", purpose="分类")


def test_client_without_api_key_fails_at_construction() -> None:
    """未配置 LLM_API_KEY：构造即抛领域异常（不在启动期，调用入口构造时报）。"""
    with pytest.raises(LLMCallFailed):
        LLMClient(Settings(llm_api_key=""))
