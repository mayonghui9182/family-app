"""每日总结手动触发入口（tasks.md 9.5）。

`POST /api/summary/trigger`：数据范围与 22:00 定时运行完全一致；当天已成功
执行过且请求未带 `force=true` → 409，body 说明当天已总结并请求确认。
运行失败（重试耗尽）不视为接口错误：响应 200，body 的 `status` 为 `failed`
并带 `error_message`（执行日志已由 summary_run 落库，不另发通知）。
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from src.services.summary_run import SummaryAlreadyRun, run_summary

router = APIRouter()


class TriggerBody(BaseModel):
    """触发请求体：force=true 跳过「当天已成功」的 409 拦截。"""

    force: bool = False


@router.post("/api/summary/trigger")
async def trigger_summary(body: TriggerBody | None = None) -> dict[str, Any]:
    """手动触发每日总结（trigger_source=manual）。"""
    try:
        result = await run_summary("manual", force=body.force if body else False)
    except SummaryAlreadyRun as exc:
        # spec：409 且 body 说明当天已总结、请求确认（force）后重跑
        raise HTTPException(status_code=409, detail=str(exc)) from exc
    return {
        "status": result.status,
        "trigger_source": result.trigger_source,
        "categories_updated": result.categories_updated,
        "error_message": result.error_message,
        "attempts": result.attempts,
        "window_since": (
            result.window_since.isoformat() if result.window_since else None
        ),
        "cancelled_drafts": result.cancelled_drafts,
    }
