"""枚举取值集合与 5 值状态机流转规则。

数据库存储采用 String + CheckConstraint（而非 PG 原生 ENUM），原因：
- 原生 ENUM 在迁移 downgrade 时需额外 drop type，回滚复杂度高；
- 行为契约只约束取值集合，CheckConstraint 足以表达且可随迁移对称删除。

取值严格对齐设计文档 §3 数据模型；TaskStatus 固定 5 值为项目硬约束。
"""

# 任务 / 子待办状态：固定 5 值（硬约束，不得增删）
TASK_STATUSES = ("draft", "pending", "in_progress", "completed", "cancelled")

# 优先级
PRIORITIES = ("high", "medium", "low")

# 任务来源
TASK_SOURCES = ("manual", "agent", "routine", "schedule", "trigger")

# 注意事项的多态目标类型
NOTE_TARGET_TYPES = ("task", "subtask")

# 规则模板类型（MVP 仅 decomposition 有行为，routine 预留）
TEMPLATE_TYPES = ("decomposition", "routine")

# 每日总结执行状态
SUMMARY_STATUSES = ("success", "failed")

# 每日总结触发来源
SUMMARY_TRIGGER_SOURCES = ("auto", "manual")

# 5 值状态机合法流转白名单（任务与子待办共用，spec task-mgmt）
STATUS_TRANSITIONS: dict[str, tuple[str, ...]] = {
    "draft": ("pending", "cancelled"),
    "pending": ("in_progress", "completed", "cancelled"),
    "in_progress": ("completed", "cancelled"),
    "completed": (),  # 终态不可复活
    "cancelled": (),  # 终态不可复活
}


def is_valid_transition(current: str, target: str) -> bool:
    """判断状态流转是否在白名单内（相同状态视为无需流转，返回 False）。"""
    return target in STATUS_TRANSITIONS.get(current, ())


def in_clause(column: str, values: tuple[str, ...]) -> str:
    """生成 CheckConstraint 用的 `column IN ('a','b')` SQL 片段。"""
    quoted = ", ".join(f"'{v}'" for v in values)
    return f"{column} IN ({quoted})"
