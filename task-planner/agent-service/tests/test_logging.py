"""JSON 结构化日志测试（tasks.md 7.1）：一行一 JSON，业务字段经 extra 透传。"""

from __future__ import annotations

import io
import json
import logging

from src.logging_setup import JsonLineFormatter, setup_logging


def test_log_record_serializes_to_single_json_line() -> None:
    """一条记录 = 一行合法 JSON，含基础字段与 extra 业务字段。"""
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonLineFormatter())
    logger = logging.getLogger("test.json_line")
    logger.handlers = [handler]
    logger.setLevel(logging.INFO)
    logger.propagate = False

    logger.info(
        "LLM 调用 purpose=%s",
        "分类",
        extra={"purpose": "分类", "elapsed_ms": 123.4, "prompt_tokens": 10},
    )

    lines = stream.getvalue().splitlines()
    assert len(lines) == 1
    payload = json.loads(lines[0])
    assert payload["msg"] == "LLM 调用 purpose=分类"
    assert payload["level"] == "INFO"
    assert payload["logger"] == "test.json_line"
    assert payload["purpose"] == "分类"
    assert payload["elapsed_ms"] == 123.4
    assert payload["prompt_tokens"] == 10
    assert "ts" in payload


def test_setup_logging_is_idempotent_and_json() -> None:
    """重复调用不叠加 handler，且输出为 JSON 行。"""
    setup_logging("INFO")
    setup_logging("INFO")
    root = logging.getLogger()
    json_handlers = [
        h for h in root.handlers if getattr(h, "_task_planner_json", False)
    ]
    assert len(json_handlers) == 1

    # 通过根 logger 实际输出一条，验证 JSON 可解析
    handler = json_handlers[0]
    record = logging.LogRecord(
        name="agent_service.test", level=logging.INFO, pathname=__file__,
        lineno=1, msg="探针", args=(), exc_info=None,
    )
    payload = json.loads(handler.format(record))
    assert payload["msg"] == "探针"


def test_exception_info_is_included_in_json() -> None:
    """异常日志：exception 字段进 JSON，仍为单行。"""
    formatter = JsonLineFormatter()
    try:
        raise ValueError("测试异常")
    except ValueError:
        import sys

        record = logging.LogRecord(
            name="x", level=logging.ERROR, pathname=__file__, lineno=1,
            msg="出错了", args=(), exc_info=sys.exc_info(),
        )
    line = formatter.format(record)
    assert "\n" not in line
    payload = json.loads(line)
    assert "ValueError" in payload["exception"]
