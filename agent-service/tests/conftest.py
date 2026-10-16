"""共享测试假件：FakeLLM / FakeDb / FakeBackend / FakeAuth + 编排器工厂。"""
import asyncio
import json
import os
from typing import Any

import pytest

# 测试必须显式提供内部密钥，不能依赖生产默认值。
os.environ.setdefault("INTERNAL_API_SECRET", "test-internal-secret")

from app.services.auth_verifier import CurrentUser
from app.services.decomposer import Decomposer

VALID_DECOMPOSE_JSON = json.dumps(
    {
        "title": "周末大扫除",
        "description": "全家参与的大扫除",
        "priority": "MEDIUM",
        "category_id": 3,
        "subtasks": [
            {"title": "擦窗", "description": "客厅和卧室", "sort_order": 1},
            {"title": "拖地", "description": "", "sort_order": 2},
            {"title": "整理阳台", "description": "", "sort_order": 3},
        ],
        "notes": ["记得多喝水休息"],
    },
    ensure_ascii=False,
)

CTX_FULL: dict[str, Any] = {
    "categories": [{"id": 3, "name": "家务"}],
    "templates": [],
    "few_shot": ["上周完成：厨房深度清洁"],
    "workload": {},
}
"""有历史参考的完整上下文。"""

CTX_COLD: dict[str, Any] = {"categories": [], "templates": [], "few_shot": [], "workload": {}}


class FakeLLM:
    """按队列回放结果的假 LLM。kind: json(流式token+返回文本)/raw/slow。"""

    def __init__(self, outcomes: list[dict[str, Any]]) -> None:
        self.outcomes = list(outcomes)
        self.calls: list[dict[str, Any]] = []

    async def stream_chat(self, messages: list[dict[str, Any]], on_token: Any = None,
                          response_format: dict[str, str] | None = None) -> str:
        self.calls.append({"messages": messages, "response_format": response_format})
        out = self.outcomes.pop(0)
        if out["kind"] == "slow":
            await asyncio.sleep(out["seconds"])
            return ""
        text = out["text"]
        for tok in out.get("tokens") or [text]:
            if on_token is not None:
                on_token(tok)
            await asyncio.sleep(0)
        return text


class FakeDb:
    def __init__(self, ctx: dict[str, Any]) -> None:
        self.ctx = ctx
        seen: list[int] = []
        self.seen = seen

    async def get_context(self, family_id: int) -> dict[str, Any]:
        assert isinstance(family_id, int), "family_id 必须 int"
        self.seen.append(family_id)
        return self.ctx


class FakeBackend:
    def __init__(self) -> None:
        self.calls: list[tuple[str, int, Any]] = []
        self.next_task_id = 501
        self.categories: list[dict[str, Any]] = [{"id": 3, "name": "家务"}]
        # B-3 家庭成员档案假件（供 LLM 推断 assignee_profile_id）
        self.profiles: list[dict[str, Any]] = [
            {"id": 11, "name": "妈妈", "relation": "妈妈", "tags": ["做饭", "买菜"]},
            {"id": 12, "name": "爸爸", "relation": "爸爸", "tags": ["开车", "修理"]},
        ]
        # B-6 save_proposal 假件：记录最后一次传入的 proposed_tasks
        self.saved_proposal: list[dict[str, Any]] | None = None
        # B-5 extract-patch 假件：记录最后一次累计补丁
        self.saved_patch: dict[str, Any] | None = None
        # B-7 追加消息假件：记录 (role, content) 序列
        self.appended_messages: list[tuple[str, str]] = []

    async def get_categories(self, family_id: int) -> list[dict[str, Any]]:
        """C-10 假件：分类清单（后端侧已按 family_id + 软删过滤）。"""
        assert isinstance(family_id, int), "family_id 必须 int"
        return self.categories

    async def get_profiles(self, family_id: int) -> list[dict[str, Any]]:
        """B-3 假件：家庭成员档案（供 LLM 推断 assignee_profile_id）。"""
        assert isinstance(family_id, int), "family_id 必须 int"
        return self.profiles

    async def get_session_context(self, family_id: int, session_id: int) -> dict[str, Any]:
        return getattr(self, "session_context", {})

    async def save_proposal(
        self, session_id: int, proposed_tasks: list[dict[str, Any]], *,
        family_id: int, message: str = "", revision: str | None = None,
    ) -> None:
        """B-6 假件：记录 proposed_tasks 供测试断言。"""
        self.saved_proposal = list(proposed_tasks)

    async def save_extracted_patch(
        self, session_id: int, extracted_patch: dict[str, Any], *, family_id: int,
    ) -> None:
        """B-5 假件：记录累计补丁供测试断言。"""
        assert isinstance(session_id, int), "session_id 必须 int"
        assert isinstance(family_id, int), "family_id 必须 int"
        self.calls.append(("save_extracted_patch", session_id, extracted_patch))
        self.saved_patch = dict(extracted_patch)


    async def append_session_message(
        self, session_id: int, role: str, content: str, *, family_id: int,
    ) -> None:
        """B-7 假件：记录追加的对话消息。"""
        assert isinstance(session_id, int), "session_id 必须 int"
        assert isinstance(family_id, int), "family_id 必须 int"
        self.calls.append(("append_session_message", session_id, {"role": role, "content": content}))
        self.appended_messages.append((role, content))


    async def create_task(self, family_id: int, payload: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(("create_task", family_id, payload))
        return {"code": 0, "data": {"id": self.next_task_id}}

    async def create_subtasks_batch(self, family_id: int, task_id: int,
                                    subtasks: list[dict[str, Any]]) -> dict[str, Any]:
        self.calls.append(("create_subtasks_batch", family_id, {"task_id": task_id, "subtasks": subtasks}))
        return {"code": 0, "data": {"created": len(subtasks)}}

    async def add_note(self, family_id: int, target_type: str, target_id: int,
                       content: str) -> dict[str, Any]:
        self.calls.append(
            ("add_note", family_id, {"target_type": target_type, "target_id": target_id, "content": content})
        )
        return {"code": 0}

    async def cancel_task(self, family_id: int, task_id: int) -> dict[str, Any]:
        self.calls.append(("cancel_task", family_id, task_id))
        return {"code": 0}


class FakeAuth:
    def __init__(self, error: Exception | None = None) -> None:
        self.error = error

    async def verify(self, family_id: int, access_token: str) -> CurrentUser:
        if self.error is not None:
            raise self.error
        return CurrentUser(nickname="妈妈", family_id=family_id, role="OWNER")


class FakeWeather:
    """tool-service 天气假件：按 city 关键词返回预设天气类型；不匹配城市返回 unknown。"""

    def __init__(self, weather_map: dict[str, str] | None = None) -> None:
        self.weather_map = weather_map or {"北京": "sunny", "上海": "rain"}
        self.calls: list[tuple[str, str]] = []

    async def fetch_weather_type(self, city: str, date_str: str) -> str:
        self.calls.append((city, date_str))
        return self.weather_map.get(city, "unknown")


def make_decomposer(llm: FakeLLM, db_ctx: dict[str, Any], backend: FakeBackend | None = None,
                    auth_error: Exception | None = None,
                    weather: FakeWeather | None = None) -> tuple[Decomposer, FakeBackend]:
    """组装全假件编排器，返回 (Decomposer, FakeBackend)。"""
    be = backend or FakeBackend()
    dec = Decomposer(  # type: ignore[arg-type]
        llm=llm, db=FakeDb(db_ctx), backend=be,
        auth=FakeAuth(auth_error), weather=weather or FakeWeather(),
    )
    return dec, be


async def collect(agen: Any) -> list[dict[str, Any]]:
    """收集 SSE 帧并解析为 [{event, data}]。"""
    frames: list[dict[str, Any]] = []
    async for raw in agen:
        event = raw.split("\n", 1)[0].removeprefix("event: ")
        body = raw.split("data: ", 1)[1].removesuffix("\n\n")
        frames.append({"event": event, "data": json.loads(body), "raw": raw})
    return frames


@pytest.fixture(autouse=True)
def _restore_settings_cache() -> Any:
    """超时用例改了 MCP_TIMEOUT 环境变量后恢复 lru_settings 单例。"""
    yield
    from app.settings import get_settings

    get_settings.cache_clear()
