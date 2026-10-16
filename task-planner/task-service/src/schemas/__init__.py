"""Pydantic 模式包：REST 请求/响应模型（后续 MCP 工具参数复用）。

按资源分文件：common（公共校验器）、task、subtask、note、category、template。
"""

from src.schemas.category import (
    CategoryCreate,
    CategoryMergeRequest,
    CategoryOut,
    CategoryUpdate,
    category_to_out,
)
from src.schemas.note import NoteCreate, NoteOut, note_to_out
from src.schemas.subtask import SubtaskCreate, SubtaskUpdate
from src.schemas.task import (
    SubtaskDetailOut,
    SubtaskOut,
    TaskCreate,
    TaskDetailOut,
    TaskOut,
    TaskUpdate,
    subtask_to_out,
    task_to_detail_out,
    task_to_out,
)
from src.schemas.template import (
    TemplateCreate,
    TemplateOut,
    TemplateTypeFilter,
    TemplateUpdate,
    TemplateVersionOut,
    VersionImportantUpdate,
    template_to_out,
    template_version_to_out,
)

__all__ = [
    "CategoryCreate",
    "CategoryMergeRequest",
    "CategoryOut",
    "CategoryUpdate",
    "NoteCreate",
    "NoteOut",
    "SubtaskCreate",
    "SubtaskDetailOut",
    "SubtaskOut",
    "SubtaskUpdate",
    "TaskCreate",
    "TaskDetailOut",
    "TaskOut",
    "TaskUpdate",
    "TemplateCreate",
    "TemplateOut",
    "TemplateTypeFilter",
    "TemplateUpdate",
    "TemplateVersionOut",
    "VersionImportantUpdate",
    "category_to_out",
    "note_to_out",
    "subtask_to_out",
    "task_to_detail_out",
    "task_to_out",
    "template_to_out",
    "template_version_to_out",
]
