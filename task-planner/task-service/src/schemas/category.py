"""分类资源的请求/响应模式（spec: task-mgmt Category management）。

约定：
- name 全局唯一（数据库层也有 unique 约束，service 层先行校验以给出中文业务码）；
- 合并只需目标分类 id，源分类来自路径参数。
"""

from __future__ import annotations

import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, field_validator

from src.models.category import Category


class CategoryCreate(BaseModel):
    """POST /api/categories 请求体。"""

    name: str = Field(min_length=1, max_length=64, description="分类名，必填非空且唯一")
    description: str | None = None

    @field_validator("name")
    @classmethod
    def _strip_name(cls, v: str) -> str:
        # 去首尾空白后再判空，避免「空格名」绕过唯一性校验
        return v.strip()


class CategoryUpdate(BaseModel):
    """PUT /api/categories/{id} 请求体：重命名（同样受唯一性约束）。"""

    name: str = Field(min_length=1, max_length=64)
    description: str | None = None

    @field_validator("name")
    @classmethod
    def _strip_name(cls, v: str) -> str:
        return v.strip()


class CategoryMergeRequest(BaseModel):
    """POST /api/categories/{id}/merge 请求体。"""

    target_category_id: uuid.UUID = Field(description="合并目标分类 id，不能与源分类相同")


class CategoryOut(BaseModel):
    """分类输出模型。"""

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    name: str
    description: str | None = None
    created_at: datetime


def category_to_out(category: Category) -> CategoryOut:
    """ORM Category → CategoryOut。"""
    return CategoryOut.model_validate(category)
