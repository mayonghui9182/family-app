"""调度包：APScheduler 每日 22:00（server-local）触发总结学习，含失败退避重试。

对外只暴露构建调度器与定时任务体两个入口；退避重试、窗口计算、执行日志都在
`src.services.summary_run` 内完成，定时与手动触发共用同一套处理步骤。
"""

from src.scheduler.daily_summary import (
    JOB_ID,
    build_scheduler,
    run_scheduled_summary,
)

__all__ = ["JOB_ID", "build_scheduler", "run_scheduled_summary"]
