"""每日 22:00 总结调度（Change 5）：APScheduler cron + 每家庭批 + 分布式锁 skip。

锁归属后端（design.md D1）：抢锁/收尾经 C-19/C-20，本文件只负责「何时跑、跑哪些家庭、
单家庭失败不阻断其它家庭」。家庭清单经 C-23（agent_reader 无 families.deleted_at 权限）。
"""
import asyncio
from typing import TYPE_CHECKING

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from loguru import logger

if TYPE_CHECKING:
    from ..services.summarizer import Summarizer

_BATCH_SIZE = 50

_summarizer: "Summarizer | None" = None


def configure(summarizer: "Summarizer") -> None:
    """注册调度前注入总结器实例（main.py startup 装配）。"""
    global _summarizer
    _summarizer = summarizer


async def run_daily_summary() -> None:
    """22:00 入口：遍历活跃家庭（每批 50）；每家庭独立 claim→跑→收尾，单家庭失败不阻断。"""
    if _summarizer is None:
        logger.warning("总结器未配置，跳过 22:00 每日总结")
        return
    family_ids = await _summarizer.backend.get_active_family_ids()
    logger.info("每日总结开始 families={}", len(family_ids))
    ran, skipped, failed = 0, 0, 0
    for start in range(0, len(family_ids), _BATCH_SIZE):
        batch = family_ids[start : start + _BATCH_SIZE]
        for fid in batch:
            try:
                if await _summarizer.run_family(fid, trigger_source="auto") is None:
                    skipped += 1
                else:
                    ran += 1
            except Exception as exc:  # noqa: BLE001 单家庭失败不阻断其它家庭
                failed += 1
                logger.error("单家庭总结失败 family={} error={}", fid, exc)
        await asyncio.sleep(0)  # 批间让出事件循环，避免长批次饿死请求
    logger.info("每日总结结束 ran={} skipped={} failed={}", ran, skipped, failed)


def register_summary_job(scheduler: AsyncIOScheduler) -> bool:
    """注册 22:00（Asia/Shanghai）每日总结 cron（Q54 定版：总结归 agent-service 调度）。"""
    scheduler.add_job(
        run_daily_summary,
        "cron",
        hour=22,
        minute=0,
        timezone="Asia/Shanghai",
        id="daily_summary",
        replace_existing=True,
    )
    logger.info("已注册 22:00 每日总结 job（Asia/Shanghai）")
    return True
