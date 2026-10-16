"""rule_templates / rule_template_versions — 统一模板表与版本历史（设计文档 §3）。

MVP 仅 type=decomposition 有行为（routine 预留）；content 用 JSONB 存整份
拆解规则（design.md D8：不拆步骤子表）。任何 content 变更 → version+1 并写
版本快照，最多保留 20 版（template-learning spec）。

两表存在循环外键（template.parent_version_id ↔ version.template_id），
parent_version_id 用 use_alter=True 延迟建约束，保证建表/删表顺序可解。
"""

import uuid
from datetime import datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from src.models.base import Base
from src.models.enums import TEMPLATE_TYPES, in_clause

# 版本历史最大保留数（超出后淘汰最旧的非重要版本，is_important 版本不自动删除）
MAX_TEMPLATE_VERSIONS = 20


class RuleTemplate(Base):
    """规则模板：decomposition（拆解规则）与 routine（例行任务）统一存储。"""

    __tablename__ = "rule_templates"
    __table_args__ = (
        CheckConstraint(in_clause("type", TEMPLATE_TYPES), name="type_enum"),
        UniqueConstraint("user_id", "learning_key", name="uq_rule_templates_owner_learning"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4, comment="主键 UUID"
    )
    type: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        index=True,
        comment="模板类型：decomposition/routine",
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False, comment="模板名称")
    category_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("categories.id"),
        nullable=True,
        index=True,
        comment="所属分类",
    )
    learning_key: Mapped[str | None] = mapped_column(
        String(80), nullable=True, comment="当前用户内冷启动自动模板的幂等键"
    )
    conditions: Mapped[dict | None] = mapped_column(
        JSONB, nullable=True, comment="触发条件（routine 用，decomposition 为 null）"
    )
    content: Mapped[dict] = mapped_column(
        JSONB,
        nullable=False,
        comment="模板内容；decomposition: {common_steps, estimated_subtask_count, notes}",
    )
    is_active: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=True,
        server_default="true",
        comment="是否启用，false 时排除出拆解候选",
    )
    last_manual_edit_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True, comment="最后一次手动编辑内容的时间"
    )
    version: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        default=1,
        server_default="1",
        comment="当前版本号（decomposition 递增，routine 固定 1）",
    )
    parent_version_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("rule_template_versions.id", use_alter=True),
        nullable=True,
        comment="当前版本所基于的历史版本（循环外键，延迟创建）",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        comment="创建时间",
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
        comment="更新时间",
    )

    # 所属分类：只读关联
    category: Mapped["Category | None"] = relationship("Category", lazy="selectin")  # noqa: F821
    # 版本历史：按版本号倒序加载，模板删除时级联删除版本；
    # 两表间存在双外键路径（versions.template_id 与 templates.parent_version_id），
    # 必须显式指定 foreign_keys 消除歧义
    versions: Mapped[list["RuleTemplateVersion"]] = relationship(
        back_populates="template",
        cascade="all, delete-orphan",
        passive_deletes=True,
        order_by="RuleTemplateVersion.version.desc()",
        lazy="selectin",
        foreign_keys="RuleTemplateVersion.template_id",
    )


class RuleTemplateVersion(Base):
    """模板版本快照：content 变更时整份写入，供回滚与学习追溯。"""

    __tablename__ = "rule_template_versions"
    __table_args__ = (
        UniqueConstraint("template_id", "version", name="uq_template_version"),
    )

    id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), primary_key=True, default=uuid.uuid4, comment="主键 UUID"
    )
    template_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("rule_templates.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
        comment="所属模板",
    )
    version: Mapped[int] = mapped_column(Integer, nullable=False, comment="版本号")
    content: Mapped[dict] = mapped_column(
        JSONB, nullable=False, comment="该版本 content 整份快照"
    )
    change_summary: Mapped[str | None] = mapped_column(
        Text, nullable=True, comment="变更摘要；手动编辑固定为「用户手动编辑」"
    )
    is_important: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        default=False,
        server_default="false",
        comment="用户标记的重要版本，超量淘汰时不自动删除",
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        comment="创建时间",
    )
    parent_version_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True),
        ForeignKey("rule_template_versions.id"),
        nullable=True,
        comment="上一版本自引用",
    )

    # 所属模板（反向引用）
    template: Mapped["RuleTemplate"] = relationship(
        "RuleTemplate",
        back_populates="versions",
        foreign_keys=[template_id],
    )
