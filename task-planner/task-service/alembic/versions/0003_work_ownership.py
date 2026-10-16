"""工作关联数据增加真实用户归属；旧数据保留 default 等待显式认领。"""

from alembic import op
import sqlalchemy as sa

revision = "0003"
down_revision = "0002"
branch_labels = None
depends_on = None

TABLES = ("categories", "subtasks", "notes", "rule_templates",
          "rule_template_versions", "summary_execution_log")


def upgrade():
    """所有旧行保持原共享空间标识，不推测真实用户。"""
    for table in TABLES:
        op.add_column(table, sa.Column("user_id", sa.String(64), nullable=False,
                                     server_default="default"))
        op.alter_column(table, "user_id", server_default=None)
        op.create_index(f"ix_{table}_user_id", table, ["user_id"])
    op.alter_column("tasks", "user_id", server_default=None)
    op.create_index("ix_tasks_user_id", "tasks", ["user_id"])
    op.drop_constraint("uq_categories_name", "categories", type_="unique")
    op.create_unique_constraint("uq_categories_owner_name", "categories", ["user_id", "name"])
    op.drop_constraint("uq_rule_templates_learning_key", "rule_templates", type_="unique")
    op.create_unique_constraint("uq_rule_templates_owner_learning", "rule_templates",
                                ["user_id", "learning_key"])


def downgrade():
    """多用户数据存在时拒绝抹去归属，避免回滚变成数据泄露。"""
    connection = op.get_bind()
    for table in (*TABLES, "tasks"):
        count = connection.execute(sa.text(
            f"SELECT count(*) FROM {table} WHERE user_id <> 'default'"
        )).scalar_one()
        if count:
            raise RuntimeError("已有真实用户数据，禁止删除归属列；请回滚至安全应用版本")
    op.drop_constraint("uq_rule_templates_owner_learning", "rule_templates", type_="unique")
    op.create_unique_constraint("uq_rule_templates_learning_key", "rule_templates", ["learning_key"])
    op.drop_constraint("uq_categories_owner_name", "categories", type_="unique")
    op.create_unique_constraint("uq_categories_name", "categories", ["name"])
    op.drop_index("ix_tasks_user_id", table_name="tasks")
    op.alter_column("tasks", "user_id", server_default="default")
    for table in reversed(TABLES):
        op.drop_index(f"ix_{table}_user_id", table_name=table)
        op.drop_column(table, "user_id")
