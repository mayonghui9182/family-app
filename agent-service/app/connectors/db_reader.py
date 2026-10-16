"""只读连接器：SQLAlchemy async + agent_reader 角色，SELECT-only，签名 family_id:int。

直接连 PostgreSQL 仅用 agent_reader 只读角色（DB 层拒绝写）；所有查询 filter 写死
family_id = :family_id，防跨家庭读取。Change 2 的 7 表 ORM 模型不在本服务重复定义，
用 SQLAlchemy Core text() 原生只读查询。

家庭隔离（§5.6 四层）：
  L2 本文件运行时守卫 family_id 必须为 int（TypeError 拒绝）；
  L3 所有 SQL filter 显式绑定 :family_id；
  L4 行级安全 RLS —— Phase 2 落地占位：
    -- ALTER TABLE tasks ENABLE ROW LEVEL SECURITY;
    -- CREATE POLICY agent_reader_scope ON tasks FOR SELECT
    --   TO agent_reader USING (family_id = current_setting('app.family_id')::int);

已知边界（如实记录，Change 7 E2E 关注）：agent_reader 对 tasks/categories 的 GRANT
排除了 deleted_at 列（V6 权限矩阵），故本文件无法过滤软删行；软删任务可能进入学习样本，
影响仅为「复现计数偏大」，不会写错数据（写 100% 经后端且后端按 family_id + 软删过滤）。
"""
import json
from typing import Any

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

from ..settings import get_settings

# 同分类近期已完成任务：一次查询带回子待办与便签（json_agg 聚合，避免 N+1）
_SQL_RECENT_COMPLETED = text(
    """
SELECT t.id AS task_id,
       (SELECT json_agg(json_build_object(
                    'title', s.title,
                    'description', s.description,
                    'sort_order', s.sort_order,
                    'is_ai_generated', s.is_ai_generated)
                ORDER BY s.sort_order, s.id)
          FROM subtasks s WHERE s.parent_task_id = t.id) AS subtasks,
       (SELECT json_agg(n.content ORDER BY n.created_at, n.id)
          FROM notes n WHERE n.target_type = 'task' AND n.target_id = t.id) AS notes
  FROM tasks t
 WHERE t.family_id = :family_id
   AND t.category_id = :category_id
   AND t.status = 'COMPLETED'
   AND t.updated_at >= NOW() - make_interval(days => :window_days)
 ORDER BY t.updated_at DESC, t.id DESC
 LIMIT :limit
"""
)


# ❌2 cancelled-draft 信号（TaskSource/TaskStatus 枚举名在 DB 侧存大写）
_SQL_CANCELLED_AGENT_SIGNAL = text(
    """
SELECT category_id, COUNT(*) AS cancelled_count FROM tasks
 WHERE family_id = :family_id AND status = 'CANCELLED' AND source = 'AGENT'
   AND category_id IS NOT NULL
   AND created_at >= NOW() - make_interval(days => :window_days)
 GROUP BY category_id HAVING COUNT(*) >= :threshold
 ORDER BY category_id
"""
)


class DbReader:
    """SELECT-only 家庭上下文读取器。"""

    def __init__(self) -> None:
        url = get_settings().db_agent_reader_url
        # 将普通或同步 PostgreSQL 地址统一为 asyncpg，满足异步引擎要求。
        # 兼容无驱动前缀及 psycopg2、psycopg 两种同步驱动地址。
        if url:
            for _p in ("postgresql+psycopg2://", "postgresql+psycopg://", "postgresql://"):
                if url.startswith(_p):
                    url = "postgresql+asyncpg://" + url[len(_p):]
                    break
        self._engine: AsyncEngine | None = create_async_engine(url) if url else None

    async def get_cancelled_agent_signal(
        self, family_id: int, window_days: int = 7, threshold: int = 5
    ) -> dict[int, int]:
        """❌2 学习信号（plan §全局约束）：近 N 天家人取消的 agent 提案按分类计数。

        口径：tasks.status='CANCELLED' AND source='AGENT'（TaskSource 枚举名，DB 存大写）
        AND created_at >= now - window_days，GROUP BY category_id HAVING count >= threshold。
        返回 {category_id: 取消次数}；agent_reader 对 tasks 有 status/source/category_id/
        created_at 列授权（afterMigrate 排除 deleted_at），无写权限 → 只出信号，写库全走后端。
        """
        self._guard(family_id)
        async with self._require_engine().connect() as conn:  # pragma: no cover（PG 真机路径，单测用假件）
            result = await conn.execute(
                _SQL_CANCELLED_AGENT_SIGNAL,
                {"family_id": family_id, "window_days": window_days, "threshold": threshold},
            )
            return {int(row._mapping["category_id"]): int(row._mapping["cancelled_count"]) for row in result}

    async def get_recent_completed(
        self, family_id: int, category_id: int, window_days: int = 30, limit: int = 10
    ) -> list[dict[str, Any]]:
        """diff 学习样本源：同分类近 N 天 COMPLETED 任务（含子待办 is_ai_generated 与便签）。

        一次查回（json_agg 聚合子待办/便签，避免 N+1）。asyncpg 对 json 列可能回字符串，
        统一由 _as_list 归一为 list。子待办按 sort_order 升序，供 diff_engine 槽位配对（D9）。
        """
        self._guard(family_id)
        if not isinstance(category_id, int):
            raise TypeError("category_id 必须是 int")
        async with self._require_engine().connect() as conn:  # pragma: no cover（PG 真机路径，单测用假件）
            result = await conn.execute(
                _SQL_RECENT_COMPLETED,
                {
                    "family_id": family_id,
                    "category_id": category_id,
                    "window_days": window_days,
                    "limit": max(1, min(limit, 50)),
                },
            )
            return [
                {
                    "task_id": int(row._mapping["task_id"]),
                    "subtasks": self._as_list(row._mapping["subtasks"]),
                    "notes": self._as_list(row._mapping["notes"]),
                }
                for row in result
            ]

    @staticmethod
    def _as_list(value: Any) -> list[Any]:
        """json_agg 结果归一：None → []；字符串 → json.loads；已是 list 原样返回。"""
        if value is None:
            return []
        if isinstance(value, str):
            try:
                parsed = json.loads(value)
            except json.JSONDecodeError:
                return []
            return parsed if isinstance(parsed, list) else []
        return value if isinstance(value, list) else []

    async def get_context(self, family_id: int) -> dict[str, Any]:
        """Step 2 上下文骨架（templates/few_shot/workload 为后续 change 预留）。

        分类清单**不在此读取**（D11）：agent_reader 对 categories 的列授权排除
        deleted_at，软删过滤只能在后端做 → 由 Decomposer 统一取 C-10。
        此处保留 categories 空键，保证调用方取值不 KeyError。
        """
        self._guard(family_id)
        return {"categories": [], "templates": [], "few_shot": [], "workload": {}}

    @staticmethod
    def _guard(family_id: int) -> None:
        """L2 隔离守卫：非 int 直接 TypeError，绝不放行构造 SQL。"""
        if not isinstance(family_id, int):
            raise TypeError("family_id 必须是 int")

    def _require_engine(self) -> AsyncEngine:
        if self._engine is None:
            raise RuntimeError("DB_AGENT_READER_URL 未配置")
        return self._engine
