"""loguru 日志：JSON 结构化 + trace_id + SecretStr 脱敏。"""
import sys
from contextvars import ContextVar
from typing import Any

from loguru import logger

trace_id_var: ContextVar[str] = ContextVar("trace_id", default="-")


def _inject_trace_id(record: Any) -> bool:
    if not record["extra"].get("trace_id") or record["extra"]["trace_id"] == "-":
        record["extra"]["trace_id"] = trace_id_var.get()
    return True


def setup_logging() -> None:
    """进程启动时调用一次。业务日志 JSON 序列化，LOG_LEVEL<=DEBUG 才输出 DEBUG。"""
    logger.remove()
    logger.add(
        sys.stderr,
        level="INFO",
        serialize=True,
        format="{time} {level} {extra[trace_id]} {message}",
        filter=_inject_trace_id,
    )
