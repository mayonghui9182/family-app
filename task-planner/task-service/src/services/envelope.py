"""统一响应封套与领域异常（tasks.md 2.4）。

契约来源：`openspec/changes/2026-09-20-task-planner-mvp/design.md` D4 —— 沿用
family-app 既有约定（`backend/src/main/java/com/familyapp/common/Result.java`）：

- 成功：``{"code": 0, "msg": "success", "data": ...}``
- 失败：非 0 的五位业务码（前三位与 HTTP 状态码一致）+ 中文提示 + ``data`` 为 ``None``
  （参数校验失败例外：``data`` 携带字段级明细，便于前端定位）

错误码取值与 backend `ResultCode` 同义同码，未来若做统一前端/CLI 只需一套解析逻辑。
面向终端用户的 ``msg`` 一律中文，适配老年用户群体。
"""

from __future__ import annotations

import enum
import logging
from typing import Any, ClassVar

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

logger = logging.getLogger("task_service.envelope")


class ResultCode(enum.Enum):
    """业务码登记表：成员值为 ``(code, http_status, msg)`` 三元组。"""

    OK = (0, 200, "success")

    BAD_REQUEST_VALIDATION = (40099, 400, "参数校验失败")
    INTERNAL_ERROR = (50000, 500, "服务器内部错误")
    NOT_FOUND_GENERIC = (40400, 404, "请求的资源不存在")
    METHOD_NOT_ALLOWED = (40500, 405, "请求方法不被允许")
    CONFLICT_GENERIC = (40900, 409, "操作与当前状态冲突")

    BAD_REQUEST_TITLE_EMPTY = (40001, 400, "任务标题不能为空")
    BAD_REQUEST_TITLE_TOO_LONG = (40002, 400, "任务标题超过 200 字")
    BAD_REQUEST_DATE_FORMAT = (40003, 400, "日期格式不正确")
    BAD_REQUEST_STATUS_TRANSITION = (40011, 400, "状态转换不合法")
    BAD_REQUEST_INVALID_PRIORITY = (40012, 400, "优先级值不合法")
    BAD_REQUEST_INVALID_SOURCE = (40013, 400, "来源值不合法")
    TASK_INVALID_TRANSITION = (40014, 400, "任务状态不能这样切换")

    CATEGORY_NAME_DUPLICATED = (40021, 400, "分类名称已存在")
    CATEGORY_MERGE_TARGET_INVALID = (40022, 400, "合并目标分类不合法")
    CATEGORY_RESERVED_UNDELETABLE = (40023, 400, "系统保留分类「未分类」不可删除")
    NOTE_CONTENT_EMPTY = (40024, 400, "注意事项内容不能为空")

    TASK_NOT_FOUND = (40401, 404, "任务不存在")
    CATEGORY_NOT_FOUND = (40402, 404, "分类不存在")
    TEMPLATE_NOT_FOUND = (40404, 404, "模板不存在")
    SUBTASK_NOT_FOUND = (40405, 404, "子待办不存在")
    NOTE_NOT_FOUND = (40406, 404, "注意事项不存在")
    SUMMARY_LOG_NOT_FOUND = (40407, 404, "总结执行记录不存在")

    TEMPLATE_INACTIVE = (46003, 400, "此模板已停用，无法生成任务")
    SUBTASK_BATCH_EMPTY = (46004, 400, "批量新增子待办内容为空，至少 1 条")

    # 与 backend 的 46502(400) 有意分歧：本子项目 spec 要求「当天已总结」返回 409
    SUMMARY_ALREADY_RAN = (40902, 409, "今天已经总结过了，如需重跑请设置 force=true")

    def __init__(self, code: int, http_status: int, msg: str) -> None:
        self.code = code
        self.http_status = http_status
        self.msg = msg


#: HTTP 状态码 → 兜底业务码（用于包装非 ApiError 的 HTTP 异常）
_STATUS_FALLBACK: dict[int, ResultCode] = {
    400: ResultCode.BAD_REQUEST_VALIDATION,
    404: ResultCode.NOT_FOUND_GENERIC,
    405: ResultCode.METHOD_NOT_ALLOWED,
    409: ResultCode.CONFLICT_GENERIC,
    500: ResultCode.INTERNAL_ERROR,
}


class ApiError(Exception):
    """业务异常基类：由异常处理器统一转成封套响应，不泄漏堆栈给调用方。

    首参既可传 :class:`ResultCode`（精确业务码），也可直接传中文提示字符串
    （业务码回落到子类默认值），避免后续大量调用点写错关键字参数。
    """

    default_code: ClassVar[ResultCode] = ResultCode.BAD_REQUEST_VALIDATION

    def __init__(
        self,
        code: ResultCode | str | None = None,
        msg: str | None = None,
        *,
        data: Any = None,
    ) -> None:
        if isinstance(code, str):
            # 只给了提示文案：把它当 msg，业务码用子类默认值
            code, msg = None, msg or code
        resolved = code or self.default_code
        self.code = resolved
        self.msg = msg or resolved.msg
        self.data = data
        super().__init__(self.msg)

    @property
    def http_status(self) -> int:
        """业务码自带的 HTTP 状态，处理器据此设置响应状态行。"""
        return self.code.http_status


class BadRequest(ApiError):
    """400：参数校验失败或输入不合法。"""

    default_code = ResultCode.BAD_REQUEST_VALIDATION


class InvalidStatusTransition(BadRequest):
    """400：5 值状态机白名单之外的流转（含终态复活）。"""

    default_code = ResultCode.TASK_INVALID_TRANSITION


class NotFound(ApiError):
    """404：资源不存在。调用方应传入具体资源码，如 ``ResultCode.TASK_NOT_FOUND``。"""

    default_code = ResultCode.NOT_FOUND_GENERIC


class Conflict(ApiError):
    """409：与当前状态冲突，如当天已总结。"""

    default_code = ResultCode.CONFLICT_GENERIC


def ok(data: Any = None, msg: str = "success") -> dict[str, Any]:
    """构造成功封套。``msg`` 固定 ``success``，与 spec 断言的响应体逐字一致。"""
    return {"code": ResultCode.OK.code, "msg": msg, "data": data}


def fail(code: ResultCode | int, msg: str | None = None, data: Any = None) -> dict[str, Any]:
    """构造失败封套。``code`` 可传登记表成员，也可传裸整数（兜底 HTTP 映射用）。"""
    if isinstance(code, ResultCode):
        return {"code": code.code, "msg": msg or code.msg, "data": data}
    return {"code": int(code), "msg": msg or "请求失败", "data": data}


def _resolve_status_code(status: int) -> tuple[int, str | None]:
    """把 HTTP 状态映射成业务码与默认 msg；未登记的状态按 ``status * 100`` 生成。"""
    known = _STATUS_FALLBACK.get(status)
    if known is not None:
        return known.code, known.msg
    return status * 100, None


def _compose_validation_msg(errors: list[dict[str, Any]]) -> str:
    """把 pydantic 校验错误压成一句中文提示，点名出错的字段。"""
    fields: list[str] = []
    for err in errors:
        parts = [
            str(part)
            for part in err.get("loc", ())
            if part not in ("body", "query", "path", "header", "cookie")
        ]
        fields.append(".".join(parts) if parts else str(err.get("type", "invalid")))
    unique = list(dict.fromkeys(fields))
    if not unique:
        return ResultCode.BAD_REQUEST_VALIDATION.msg
    return "参数校验失败：" + "、".join(unique)


async def _handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
    """领域异常 → 封套响应；业务码自带 HTTP 状态。"""
    return JSONResponse(status_code=exc.http_status, content=fail(exc.code, exc.msg, exc.data))


async def _handle_validation_error(
    request: Request, exc: RequestValidationError
) -> JSONResponse:
    """请求体/查询参数校验失败 → 400 + 字段明细。"""
    errors = list(exc.errors())
    details = [
        {
            "loc": [str(part) for part in err.get("loc", ())],
            "msg": str(err.get("msg", "")),
            "type": str(err.get("type", "")),
        }
        for err in errors
    ]
    body = fail(
        ResultCode.BAD_REQUEST_VALIDATION,
        _compose_validation_msg(errors),
        details,
    )
    return JSONResponse(status_code=400, content=body)


async def _handle_http_exception(
    request: Request, exc: StarletteHTTPException
) -> JSONResponse:
    """框架层 HTTP 异常（含未匹配路由的 404）→ 同一套封套。"""
    code, default_msg = _resolve_status_code(exc.status_code)
    msg = exc.detail if isinstance(exc.detail, str) and exc.detail else default_msg
    headers = getattr(exc, "headers", None)
    return JSONResponse(status_code=exc.status_code, content=fail(code, msg), headers=headers)


async def _handle_unexpected_error(request: Request, exc: Exception) -> JSONResponse:
    """未捕获异常 → 500 封套；细节只进日志，不回传调用方。"""
    logger.exception("未处理异常：%s %s", request.method, request.url.path)
    return JSONResponse(status_code=500, content=fail(ResultCode.INTERNAL_ERROR))


def register_exception_handlers(app: FastAPI) -> None:
    """在 FastAPI 实例上注册全部异常处理器，保证任何路径都返回统一封套。"""
    app.add_exception_handler(ApiError, _handle_api_error)
    app.add_exception_handler(RequestValidationError, _handle_validation_error)
    app.add_exception_handler(StarletteHTTPException, _handle_http_exception)
    app.add_exception_handler(Exception, _handle_unexpected_error)


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
