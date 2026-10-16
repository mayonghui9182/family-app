"""ORM 模型包。

统一导出 `Base` 与各表模型，供 Alembic（`alembic/env.py`）与应用运行时共用同一份 metadata。
表清单见设计文档 §3：tasks、subtasks、notes、categories、rule_templates、
rule_template_versions、summary_execution_log。
"""

from src.models.base import Base
from src.models.category import RESERVED_CATEGORY_NAME, Category
from src.models.enums import (
    NOTE_TARGET_TYPES,
    PRIORITIES,
    STATUS_TRANSITIONS,
    SUMMARY_STATUSES,
    SUMMARY_TRIGGER_SOURCES,
    TASK_SOURCES,
    TASK_STATUSES,
    TEMPLATE_TYPES,
    is_valid_transition,
)
from src.models.note import Note
from src.models.rule_template import MAX_TEMPLATE_VERSIONS, RuleTemplate, RuleTemplateVersion
from src.models.subtask import Subtask
from src.models.summary_log import SummaryExecutionLog
from src.models.task import Task

__all__ = [
    "Base",
    "Category",
    "Note",
    "RuleTemplate",
    "RuleTemplateVersion",
    "Subtask",
    "SummaryExecutionLog",
    "Task",
    "RESERVED_CATEGORY_NAME",
    "MAX_TEMPLATE_VERSIONS",
    "NOTE_TARGET_TYPES",
    "PRIORITIES",
    "STATUS_TRANSITIONS",
    "SUMMARY_STATUSES",
    "SUMMARY_TRIGGER_SOURCES",
    "TASK_SOURCES",
    "TASK_STATUSES",
    "TEMPLATE_TYPES",
    "is_valid_transition",
]
