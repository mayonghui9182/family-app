"""JSON 结构化日志：一行一 JSON，输出到 stdout（tasks.md 7.1）。

约定：
- 基础字段 ts/level/logger/msg 固定，业务字段经 `extra` 透传
  （LLM：purpose/prompt_summary/elapsed_ms/token 数；MCP：tool/elapsed_ms）
- 重复调用幂等：只替换自己挂的 handler，不动 pytest 等外部挂载的 handler
- 关闭 uvicorn access 日志，避免非 JSON 行混入 stdout
"""

from __future__ import annotations

import json
import logging
import sys
from datetime import datetime

# LogRecord 内置属性：透传 extra 时需要排除，避免把内部字段写进 JSON；
# `color_message` 是 uvicorn 私有字段（带 ANSI 占位符），同样排除
_RESERVED_ATTRS = set(vars(logging.makeLogRecord({})).keys()) | {
    "message",
    "asctime",
    "color_message",
}


class JsonLineFormatter(logging.Formatter):
    """把 LogRecord 序列化为单行 JSON（中文不转义，便于排障阅读）。"""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict = {
            "ts": datetime.fromtimestamp(record.created).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        # 业务 extra 字段（prompt 摘要/耗时/token、工具名/耗时等）
        for key, value in vars(record).items():
            if key not in _RESERVED_ATTRS:
                payload[key] = value
        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)
        return json.dumps(payload, ensure_ascii=False, default=str)


def setup_logging(level: str = "INFO") -> None:
    """初始化根 logger：stdout + JSON 行格式（幂等）。"""
    root = logging.getLogger()
    root.setLevel(level.upper())
    # 只移除自己之前挂的 handler，保留 pytest caplog 等外部 handler
    root.handlers = [
        h for h in root.handlers if not getattr(h, "_task_planner_json", False)
    ]
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonLineFormatter())
    handler._task_planner_json = True  # type: ignore[attr-defined]
    root.addHandler(handler)
    # uvicorn 自带日志配置会给 uvicorn/uvicorn.error 挂私有 handler（非 JSON），
    # 这里清空其 handler 并改为向根 logger 传播，保证全部输出都是一行一 JSON；
    # access 日志内容价值低且含请求细节，直接禁用
    for name in ("uvicorn", "uvicorn.error"):
        uv_logger = logging.getLogger(name)
        uv_logger.handlers = []
        uv_logger.propagate = True
    logging.getLogger("uvicorn.access").disabled = True
