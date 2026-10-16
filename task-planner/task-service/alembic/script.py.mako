"""${message}

Revision ID: ${up_revision}
Revises: ${down_revision | comma,n}
Create Date: ${create_date}

"""
from __future__ import annotations

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
${imports if imports else ""}

# 修订标识，Alembic 迁移链的唯一依据
revision: str = ${repr(up_revision)}
down_revision: Union[str, None] = ${repr(down_revision)}
branch_labels: Union[str, Sequence[str], None] = ${repr(branch_labels)}
depends_on: Union[str, Sequence[str], None] = ${repr(depends_on)}


def upgrade() -> None:
    """正向迁移。"""
    ${upgrades if upgrades else "pass"}


def downgrade() -> None:
    """逆向回滚：必须与 upgrade 完全对称，保证 downgrade base 可用（tasks.md 2.2）。"""
    ${downgrades if downgrades else "pass"}
