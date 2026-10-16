"""保存学习证据与并发版本约束。"""

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    """旧任务不回填推测快照，缺失证据时不参与自动学习。"""
    op.add_column(
        "rule_templates",
        sa.Column(
            "learning_key",
            sa.String(80),
            nullable=True,
            comment="冷启动自动模板的幂等键",
        ),
    )
    op.create_unique_constraint(
        "uq_rule_templates_learning_key", "rule_templates", ["learning_key"]
    )
    op.add_column(
        "tasks",
        sa.Column(
            "ai_snapshot",
            postgresql.JSONB(),
            nullable=True,
            comment="不可变的原始提案，含模板归属和子待办快照",
        ),
    )
    op.add_column(
        "rule_templates",
        sa.Column(
            "last_manual_edit_at",
            sa.DateTime(timezone=True),
            nullable=True,
            comment="最后一次手动编辑内容的时间",
        ),
    )
    op.execute("""
        UPDATE rule_templates AS t SET last_manual_edit_at = v.edited_at
        FROM (SELECT template_id, max(created_at) AS edited_at
              FROM rule_template_versions WHERE change_summary = '用户手动编辑'
              GROUP BY template_id) AS v WHERE t.id = v.template_id
    """)
    # 若旧库已有重复版本，迁移明确失败，由操作人员核对历史；不静默删除证据。
    op.create_unique_constraint(
        "uq_template_version", "rule_template_versions", ["template_id", "version"]
    )


def downgrade() -> None:
    """撤销本次新增字段和约束。"""
    op.drop_constraint("uq_template_version", "rule_template_versions", type_="unique")
    op.drop_column("rule_templates", "last_manual_edit_at")
    op.drop_column("tasks", "ai_snapshot")
    op.drop_constraint(
        "uq_rule_templates_learning_key", "rule_templates", type_="unique"
    )
    op.drop_column("rule_templates", "learning_key")
