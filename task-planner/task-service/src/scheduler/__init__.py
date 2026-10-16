"""定时与触发逻辑包（APScheduler）。

MVP 内 task-service 不承载独立定时器：过期 draft 的清理随每日总结批量执行（design.md D5）。
"""
