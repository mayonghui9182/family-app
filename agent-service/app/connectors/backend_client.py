"""backend C 组内部 API 客户端（写操作 100% 经此；只读上下文走 db_reader）。"""
from typing import Any, cast

import httpx

from ..settings import get_settings


def _note_target(target_type: str) -> str:
    """C-8 target_type 归一：后端只接受小写 task/subtask，非法值早失败（不发请求）。"""
    normalized = target_type.strip().lower()
    if normalized not in ("task", "subtask"):
        raise ValueError(f"target_type 只允许 task/subtask，收到 {target_type!r}")
    return normalized


class BackendClient:
    """httpx.AsyncClient 封装 Change 2 的 C 组内部端点 + Change 5 的 C-19~C-23。

    家庭作用域：family_id 作为 query 参数（对齐 backend InternalAgentController 的
    @RequestParam("family_id")）；X-Internal-Secret 头由 backend 拦截器恒定时间校验。
    注：规格 §3.4 原文写「X-Family-Id 头」，但 Change 2 落库实现为 query 参数，待后续对齐。
    信封：所有响应均为 Result{code,msg,data}；本类读端点统一解包 data（非 0 由 raise_for_status 前置拦截）。
    """

    def __init__(self) -> None:
        settings = get_settings()
        self._secret = settings.internal_api_secret.get_secret_value()
        self._client = httpx.AsyncClient(base_url=settings.backend_base_url, timeout=30.0)

    def _headers(self, family_id: int) -> dict[str, str]:
        """校验 family_id 为 int（非 int → TypeError，不发出请求）；返回内部秘钥头。"""
        if not isinstance(family_id, int):
            raise TypeError("family_id 必须是 int")
        return {"X-Internal-Secret": self._secret}

    @staticmethod
    def _unwrap(resp: httpx.Response) -> Any:
        """Result 信封解包（读端点用）；data 可为 null。"""
        resp.raise_for_status()
        return resp.json().get("data")

    async def create_task(self, family_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        """C-3 创建任务（source 强制 agent）。"""
        resp = await self._client.post(
            "/api/internal/agent/tasks",
            params={"family_id": family_id},
            headers=self._headers(family_id),
            json=payload,
        )
        resp.raise_for_status()
        return cast(dict[str, Any], resp.json())

    async def create_subtasks_batch(
        self, family_id: int, task_id: int, subtasks: list[dict[str, Any]]
    ) -> dict[str, Any]:
        """C-4 批量子待办。"""
        resp = await self._client.post(
            "/api/internal/agent/subtasks/batch",
            params={"family_id": family_id},
            headers=self._headers(family_id),
            json={"task_id": task_id, "subtasks": subtasks},
        )
        resp.raise_for_status()
        return cast(dict[str, Any], resp.json())

    async def add_note(
        self, family_id: int, target_type: str, target_id: int, content: str
    ) -> dict[str, Any]:
        """C-8 新增便签；target_type 归一为后端枚举小写名（'TASK' → 'task'）。"""
        resp = await self._client.post(
            "/api/internal/agent/notes",
            params={"family_id": family_id},
            headers=self._headers(family_id),
            json={"target_type": _note_target(target_type), "target_id": target_id, "content": content},
        )
        resp.raise_for_status()
        return cast(dict[str, Any], resp.json())

    async def cancel_task(self, family_id: int, task_id: int) -> dict[str, Any]:
        """C-5 回滚用软取消（status=CANCELLED）。Change 2 无 delete_task 端点，回滚走状态软删。"""
        resp = await self._client.put(
            f"/api/internal/agent/tasks/{task_id}",
            params={"family_id": family_id},
            headers=self._headers(family_id),
            json={"status": "CANCELLED"},
        )
        resp.raise_for_status()
        return cast(dict[str, Any], resp.json())

    async def get_categories(self, family_id: int) -> list[dict[str, Any]]:
        """C-10 分类列表。"""
        resp = await self._client.get(
            "/api/internal/agent/categories",
            params={"family_id": family_id},
            headers=self._headers(family_id),
        )
        resp.raise_for_status()
        return cast(list[dict[str, Any]], resp.json().get("data", []))

    async def get_templates(
        self, family_id: int, type_: str | None = None, is_active: bool | None = None
    ) -> list[dict[str, Any]]:
        """C-11 模板列表（type/is_active 可选过滤）。"""
        params: dict[str, Any] = {"family_id": family_id}
        if type_ is not None:
            params["type"] = type_
        if is_active is not None:
            params["is_active"] = str(is_active).lower()
        resp = await self._client.get(
            "/api/internal/agent/templates",
            params=params,
            headers=self._headers(family_id),
        )
        return cast(list[dict[str, Any]], self._unwrap(resp) or [])

    async def get_decomposition_templates(self, family_id: int) -> list[dict[str, Any]]:
        """C-11 特化：启用的 decomposition 模板（❌2 警告定位模板用）。"""
        return await self.get_templates(family_id, type_="decomposition", is_active=True)

    async def create_new_template_version(
        self,
        family_id: int,
        template_id: int,
        content: dict[str, Any],
        change_summary: str,
        is_important: bool = False,
    ) -> dict[str, Any]:
        """C-18 提交模板新版本（写操作唯一入口，事务内落库）。"""
        resp = await self._client.post(
            f"/api/internal/agent/templates/{template_id}/new-version",
            params={"family_id": family_id},
            headers=self._headers(family_id),
            json={
                "content": content,
                "change_summary": change_summary,
                "is_important": is_important,
            },
        )
        resp.raise_for_status()
        return cast(dict[str, Any], resp.json())

    async def claim_summary_lock(
        self, family_id: int, trigger_source: str = "auto", force: bool = False
    ) -> dict[str, Any]:
        """C-19 抢当日总结锁 → {acquired, log_id}（未抢到不抛错，由调用方 skip）。"""
        resp = await self._client.post(
            "/api/internal/agent/summary-log/claim",
            params={"family_id": family_id, "force": str(force).lower()},
            headers=self._headers(family_id),
            json={"trigger_source": trigger_source},
        )
        return cast(dict[str, Any], self._unwrap(resp) or {})

    async def finish_summary_log(
        self,
        family_id: int,
        log_id: int,
        status: str,
        categories_updated: list[int],
        failed_categories: list[dict[str, Any]],
    ) -> dict[str, Any]:
        """C-20 收尾：running → success/failed。"""
        resp = await self._client.put(
            f"/api/internal/agent/summary-log/{log_id}",
            params={"family_id": family_id},
            headers=self._headers(family_id),
            json={
                "status": status,
                "categories_updated": categories_updated,
                "failed_categories": failed_categories,
            },
        )
        resp.raise_for_status()
        return cast(dict[str, Any], resp.json())

    async def append_change_summary(self, family_id: int, template_id: int, append: str) -> dict[str, Any]:
        """C-21 追加中文变更说明到最新版（❌2 警告；不产生新版本）。"""
        resp = await self._client.post(
            f"/api/internal/agent/templates/{template_id}/change-summary",
            params={"family_id": family_id},
            headers=self._headers(family_id),
            json={"append": append},
        )
        resp.raise_for_status()
        return cast(dict[str, Any], resp.json())

    async def cleanup_template_versions(self, family_id: int) -> int:
        """C-22 版本保留策略清理（重要永久 / 普通 30 天 / 每模板最少 3 条）→ 删除条数。"""
        resp = await self._client.post(
            "/api/internal/agent/template-versions/cleanup",
            params={"family_id": family_id},
            headers=self._headers(family_id),
        )
        data = self._unwrap(resp) or {}
        return int(cast(dict[str, Any], data).get("deleted", 0))

    async def get_active_family_ids(self) -> list[int]:
        """C-23 未软删家庭 ID 升序（22:00 调度批源；无家庭上下文故不做 family_id 守卫）。"""
        resp = await self._client.get(
            "/api/internal/agent/active-families",
            headers={"X-Internal-Secret": self._secret},
        )
        data = self._unwrap(resp) or []
        return [int(x) for x in cast(list[Any], data)]

    async def get_template_versions(self, family_id: int, template_id: int, limit: int = 10) -> list[dict[str, Any]]:
        """C-24 模板版本历史（最新在前）：diff 基线 + ❌2 学习轨迹/幂等判定。"""
        resp = await self._client.get(
            f"/api/internal/agent/templates/{template_id}/versions",
            params={"family_id": family_id, "limit": limit},
            headers=self._headers(family_id),
        )
        data = self._unwrap(resp) or {}
        return cast(list[dict[str, Any]], cast(dict[str, Any], data).get("versions", []))

    async def get_profiles(self, family_id: int) -> list[dict[str, Any]]:
        """B-3 列出家庭所有未删除人员档案（供 LLM 推断 assignee_profile_id）。

        POST /api/internal/agent/profiles/get-by-family；family_id 作为 body 字段。
        返回 PersonProfileResponse 列表（id/name/relation/tags/gender/birthday 等）。
        """
        resp = await self._client.post(
            "/api/internal/agent/profiles/get-by-family",
            headers=self._headers(family_id),
            json={"family_id": family_id},
        )
        resp.raise_for_status()
        return cast(list[dict[str, Any]], resp.json().get("data", []))

    async def get_session_context(self, family_id: int, session_id: int) -> dict[str, Any]:
        """仅读取当前家庭会话，终态和跨家庭会话由后端拒绝。"""
        resp = await self._client.get(
            f"/api/internal/agent/sessions/{session_id}/context",
            params={"family_id": family_id}, headers=self._headers(family_id),
        )
        resp.raise_for_status()
        return cast(dict[str, Any], resp.json()["data"])

    async def save_proposal(
        self, session_id: int, proposed_tasks: list[dict[str, Any]], *,
        family_id: int, message: str = "", revision: str | None = None,
    ) -> None:
        """B-6 把 AI 拆解草稿写回 session.proposed_tasks，供 D-5 confirm 批量写 tasks 表。

        A-1 SSE 交互式拆解专用：agent-service 不直接写业务表，只存草稿到 session；
        用户确认后由 backend DecomposeSessionService.confirm 原子写入。
        """
        if not isinstance(session_id, int):
            raise TypeError("session_id 必须是 int")
        resp = await self._client.post(
            f"/api/internal/agent/sessions/{session_id}/save-proposal",
            headers={"X-Internal-Secret": self._secret},
            params={"family_id": family_id},
            json={"proposed_tasks": proposed_tasks, "message": message, "revision": revision},
        )
        resp.raise_for_status()

    async def save_extracted_patch(
        self, session_id: int, extracted_patch: dict[str, Any], *, family_id: int,
    ) -> None:
        """B-5 把访谈累计补丁写回 session.extracted_profile_patch，供 D-5/D-6 确认时合并到档案。

        family_id 与 session_id 联合限定会话；仅 ACTIVE 会话允许写。
        """
        if not isinstance(session_id, int):
            raise TypeError("session_id 必须是 int")
        resp = await self._client.post(
            f"/api/internal/agent/sessions/{session_id}/extract-patch",
            headers={"X-Internal-Secret": self._secret},
            params={"family_id": family_id},
            json={"extracted_patch": extracted_patch},
        )
        resp.raise_for_status()


    async def append_session_message(
        self, session_id: int, role: str, content: str, *, family_id: int,
    ) -> None:
        """B-7 追加访谈对话消息到 session.messages（role ∈ user/assistant；cap 20 由后端裁剪）。"""
        if not isinstance(session_id, int):
            raise TypeError("session_id 必须是 int")
        resp = await self._client.post(
            f"/api/internal/agent/sessions/{session_id}/messages",
            headers={"X-Internal-Secret": self._secret},
            params={"family_id": family_id},
            json={"role": role, "content": content},
        )
        resp.raise_for_status()


    async def aclose(self) -> None:
        await self._client.aclose()
