"""业务逻辑包：状态机校验、响应封套、模板版本策略等。

REST 与 MCP 两条入口共用本层，保证规则只有一份实现。
"""

from src.services.envelope import (
    ApiError,
    BadRequest,
    Conflict,
    InvalidStatusTransition,
    NotFound,
    ResultCode,
    fail,
    ok,
    register_exception_handlers,
)

__all__ = [
    "ApiError",
    "BadRequest",
    "Conflict",
    "InvalidStatusTransition",
    "NotFound",
    "ResultCode",
    "fail",
    "ok",
    "register_exception_handlers",
]
