"""每日总结调度（tasks.md 9.5）：APScheduler 每日 22:00（settings.tz）执行。

调度体只是 `run_summary("auto")` 的薄包装：退避重试、失败落日志都在
summary_run 内部完成，这里兜底捕获意外异常，避免打断调度器主循环。
`coalesce=True + max_instances=1`：仅合并进程运行期间的延迟触发；内存调度不补跑停机期间的任务。
"""

from __future__ import annotations

import logging
import httpx
from src.work_auth import current_identity, verify_identity

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.cron import CronTrigger

from src.config import settings
from src.services.summary_run import run_summary

logger = logging.getLogger("agent_service.summary")

# 调度任务标识（日志排查与测试断言用）
JOB_ID = "daily-summary"


async def run_scheduled_summary() -> None:
    """定时任务体：trigger_source=auto，异常只留痕不上抛。"""
    try:
        if not settings.internal_api_secret:
            logger.warning("内部密钥未配置，跳过工作自动总结")
            return
        url = settings.mcp_task_service_url.rsplit("/mcp", 1)[0] + "/internal/work-users"
        async with httpx.AsyncClient(timeout=5.0) as client:
            response = await client.get(url, headers={"X-Internal-Secret": settings.internal_api_secret})
            response.raise_for_status()
            users = response.json()["userIds"]
        for user_id in users:
            identity_token = None
            try:
                identity = await verify_identity(
                    {"x-internal-secret": settings.internal_api_secret, "x-work-user-id": user_id},
                    backend_url=settings.backend_base_url, internal_secret=settings.internal_api_secret)
                identity_token = current_identity.set(identity)
                result = await run_summary("auto")
                logger.info("用户工作总结结束 status=%s attempts=%d", result.status, result.attempts)
            except Exception:
                logger.warning("用户工作总结失败，继续其他用户")
            finally:
                if identity_token is not None:
                    current_identity.reset(identity_token)
    except Exception:
        logger.exception("定时总结意外失败")


def build_scheduler() -> AsyncIOScheduler:
    """构建（不启动）每日总结调度器：由 main.py 的 lifespan 启停。"""
    scheduler = AsyncIOScheduler(timezone=settings.tz)
    scheduler.add_job(
        run_scheduled_summary,
        CronTrigger(
            hour=settings.summary_hour,
            minute=settings.summary_minute,
            timezone=settings.tz,
        ),
        id=JOB_ID,
        coalesce=True,
        max_instances=1,
        misfire_grace_time=3600,
    )
    return scheduler
