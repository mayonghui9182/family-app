"""规则模板与版本历史的请求/响应模式（spec: template-learning）。

约定：
- decomposition 模板的 content 三字段（common_steps / estimated_subtask_count /
  notes）在请求体中平铺，service 层组装成整份 JSONB 存储；
- routine 模板本期只存不评估，创建时允许 type=routine 但不校验 conditions；
- 版本行只读输出，重要标记通过专用请求体切换。
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from src.models.enums import TEMPLATE_TYPES
from src.models.rule_template import RuleTemplate, RuleTemplateVersion


def normalize_steps(steps: list[str]) -> list[str]:
    """步骤列表规范化：逐条去首尾空白并丢弃空串，保证 content 存储整洁。"""
    return [step.strip() for step in steps if step and step.strip()]


class TemplateCreate(BaseModel):
    """POST /api/templates 请求体。"""

    name: str = Field(min_length=1, max_length=255, description="模板名称，必填非空")
    type: str = Field(
        default="decomposition", description="模板类型：decomposition/routine"
    )
    category_id: uuid.UUID | None = Field(default=None, description="所属分类，可为空")
    common_steps: list[str] = Field(default_factory=list, description="常见步骤列表")
    estimated_subtask_count: int = Field(default=0, ge=0, description="预期子待办数量")
    notes: str = Field(default="", description="注意事项文本")

    @field_validator("name")
    @classmethod
    def _strip_name(cls, v: str) -> str:
        return v.strip()

    @field_validator("type")
    @classmethod
    def _check_type(cls, v: str) -> str:
        if v not in TEMPLATE_TYPES:
            raise ValueError(f"模板类型必须是 {'/'.join(TEMPLATE_TYPES)} 之一")
        return v

    @field_validator("common_steps")
    @classmethod
    def _clean_steps(cls, v: list[str]) -> list[str]:
        return normalize_steps(v)


class TemplateUpdate(BaseModel):
    """PUT /api/templates/{id} 请求体：content 三字段、name 与 is_active 均可选。

    content 任一字段发生实际变化才触发 version+1 与版本快照；
    仅切换 is_active 不产生新版本（spec 只约束 content 变更）。
    """

    name: str | None = Field(default=None, min_length=1, max_length=255)
    common_steps: list[str] | None = None
    estimated_subtask_count: int | None = Field(default=None, ge=0)
    notes: str | None = None
    is_active: bool | None = None

    @field_validator("name")
    @classmethod
    def _strip_name(cls, v: str | None) -> str | None:
        return v.strip() if v is not None else None

    @field_validator("common_steps")
    @classmethod
    def _clean_steps(cls, v: list[str] | None) -> list[str] | None:
        return normalize_steps(v) if v is not None else None


class VersionImportantUpdate(BaseModel):
    """PUT /api/templates/{id}/versions/{version_id} 请求体：切换重要标记。"""

    is_important: bool = Field(description="true 后该版本永不被保留策略自动删除")


class TemplateOut(BaseModel):
    """模板输出模型（category_name 由关联派生，便于前端直接展示）。"""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    type: str
    name: str
    category_id: uuid.UUID | None = None
    category_name: str | None = None
    learning_key: str | None = None
    conditions: dict | None = None
    content: dict
    is_active: bool
    version: int
    last_manual_edit_at: datetime | None = None
    parent_version_id: uuid.UUID | None = None
    created_at: datetime
    updated_at: datetime


class TemplateVersionOut(BaseModel):
    """版本快照输出模型。"""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    version: int
    content: dict
    change_summary: str | None = None
    is_important: bool
    parent_version_id: uuid.UUID | None = None
    created_at: datetime


#: 列表接口的 type 过滤参数：只接受登记过的模板类型，其余直接 400
TemplateTypeFilter = Literal["decomposition", "routine"]


def template_to_out(template: RuleTemplate) -> TemplateOut:
    """ORM RuleTemplate → TemplateOut（补派生 category_name）。"""
    out = TemplateOut.model_validate(template)
    out.category_name = (
        template.category.name if template.category is not None else None
    )
    return out


def template_version_to_out(version: RuleTemplateVersion) -> TemplateVersionOut:
    """ORM RuleTemplateVersion → TemplateVersionOut。"""
    return TemplateVersionOut.model_validate(version)
