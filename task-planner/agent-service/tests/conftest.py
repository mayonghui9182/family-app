"""组 8/9 测试公共设施：桩 LLM transport + FakeMcp + SSE 解析。

- `ScriptedTransport`：按 system prompt 关键词识别用途（分类/模板匹配/拆解/总结），
  按脚本顺序返回 JSON 响应，可注入单用途延迟（超时场景用）
- `FakeMcp`：替换各业务模块的 `call_tool`，记录调用、可预置返回与异常
- `parse_sse`：把 SSE 文本解析为 [(事件名, data dict)]
- `summary_env`：组 9 夹具，把 FakeMcp 挂到总结链路全部模块并安装桩 LLM
"""

from __future__ import annotations

import importlib
import json
from datetime import date
from types import SimpleNamespace
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient

from src.llm.client import LLMClient
from src.main import app
from src.work_auth import WorkIdentity, current_identity


@pytest.fixture(autouse=True)
def authenticated_work_identity(monkeypatch):
    """业务测试使用显式身份桩，认证负向测试另行验证。"""
    identity = WorkIdentity("1", {"X-Access-Token": "a" * 64})
    async def verified(headers, **kwargs):
        return identity
    monkeypatch.setattr("src.work_auth.verify_identity", verified)
    token = current_identity.set(identity)
    yield
    current_identity.reset(token)
from src.services.summary_collect import CategoryGroup, TaskDiff

# system prompt 中的用途标识（与各 .j2 模板首行一致）
_PURPOSE_MARKERS = {
    "classification": "工作任务分类助手",
    "template_match": "模板匹配助手",
    "decomposition": "任务拆解助手",
    "summary": "任务模板学习助手",
}

# 写回/日志类工具：直接回显入参，断言时看 calls_of 或返回体都可以
_ECHO_TOOLS = {
    "create_rule_template",
    "update_rule_template",
    "log_summary_execution",
}

# 总结链路中会调用 MCP 的模块（存在才挂桩）
_SUMMARY_MODULES = (
    "src.services.summary_collect",
    "src.services.summary_learn",
    "src.services.summary_run",
    "src.routes.summary",
)

# 默认可运行脚本：命中分类 + 3 条子待办（reasoning 足够长以产生多个分块）
DEFAULT_SCRIPTS: dict[str, list[Any]] = {
    "classification": [{"category": "市场推广", "confidence": 0.9}],
    "decomposition": [
        {
            "task_title": "准备产品发布会",
            "task_description": "下周三产品发布会的完整准备工作",
            "subtasks": [
                {"title": "确定演示主题", "description": "明确20分钟演示的核心信息", "notes": ""},
                {"title": "制作演示PPT", "description": "按主题制作幻灯片", "notes": "提前测试投影设备"},
                {"title": "现场彩排", "description": "完整走一遍发布流程", "notes": ""},
            ],
            "agent_reasoning": (
                "结合发布会的通用流程与过往经验，按准备顺序拆解为三条可执行的子待办，"
                "覆盖内容确定、材料制作与现场演练三个环节。"
            ),
        }
    ],
}


class _Response:
    """桩 LLM 响应：duck typing choices[0].message.content 与 usage。"""

    def __init__(self, content: str) -> None:
        self.choices = [SimpleNamespace(message=SimpleNamespace(content=content))]
        self.usage = SimpleNamespace(prompt_tokens=10, completion_tokens=5)


class ScriptedTransport:
    """LLM 桩：按用途脚本化返回，记录每次调用的用途与 system prompt。"""

    def __init__(
        self,
        scripts: dict[str, list[Any]],
        delays: dict[str, float] | None = None,
    ) -> None:
        self._scripts = {purpose: list(items) for purpose, items in scripts.items()}
        self._delays = delays or {}
        self.calls: list[str] = []
        self.prompts: list[tuple[str, str]] = []
        # 组装 chat.completions.create 调用链
        outer = self

        class _Completions:
            async def create(self, **kwargs: Any) -> _Response:
                return await outer._create(**kwargs)

        class _Chat:
            def __init__(self) -> None:
                self.completions = _Completions()

        self.chat = _Chat()

    async def _create(self, **kwargs: Any) -> _Response:
        import asyncio

        system = kwargs["messages"][0]["content"]
        purpose = next(
            (p for p, marker in _PURPOSE_MARKERS.items() if marker in system), None
        )
        assert purpose is not None, f"未识别用途的 system prompt：{system[:80]}"
        self.calls.append(purpose)
        self.prompts.append((purpose, system))
        if purpose in self._delays:
            await asyncio.sleep(self._delays[purpose])
        queue = self._scripts.get(purpose)
        assert queue, f"用途 {purpose} 的脚本已耗尽（意外的额外调用）"
        item = queue.pop(0)
        content = item if isinstance(item, str) else json.dumps(item, ensure_ascii=False)
        return _Response(content)


class FakeMcp:
    """MCP 工具桩：记录调用，按名称返回预置数据或抛出预置异常。"""

    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.data: dict[str, Any] = {
            "get_categories": [
                {"id": "cat-mkt", "name": "市场推广", "description": "活动与推广"},
                {"id": "cat-uncat", "name": "未分类", "description": None},
            ],
            # 启用中的 decomposition 模板默认为空（冷启动路径）
            "get_rule_templates_active": [],
            # 未过滤 is_active 时可见的模板（含停用的，用于验证过滤）
            "get_rule_templates": [],
            "get_completed_tasks_by_category": {"category": "市场推广", "tasks": []},
            "get_current_workload": {
                "unfinished_count": 1,
                "priority_distribution": {"high": 1, "medium": 0, "low": 0},
                "due_soon_count": 0,
            },
            # ---- 组 9 每日总结链路 ----
            # 窗口内变动的任务（TaskOut 数组）
            "get_tasks_changed_since": [],
            # task_id -> 任务详情（含 subtasks，各带 is_ai_generated）
            "task_details": {},
            # category_id -> 模板行数组；预置后 get_rule_templates 走按分类返回
            "templates_by_category": None,
            "get_template_versions": [],
            "get_summary_executions": [],
            "cancel_expired_drafts": {"cancelled_count": 0, "task_ids": []},
        }
        self.errors: dict[str, Exception] = {}

    def set_data(self, name: str, value: Any) -> None:
        self.data[name] = value

    def set_error(self, name: str, exc: Exception) -> None:
        self.errors[name] = exc

    def calls_of(self, name: str) -> list[dict[str, Any]]:
        return [args for called, args in self.calls if called == name]

    async def __call__(self, name: str, arguments: dict[str, Any] | None = None) -> Any:
        args = arguments or {}
        self.calls.append((name, args))
        if name in self.errors:
            raise self.errors[name]
        if name == "get_rule_templates":
            by_category = self.data.get("templates_by_category")
            if by_category is not None:
                # 组 9：按分类返回该分类的模板（采集与写回路径）
                rows = by_category.get(str(args.get("category_id")), [])
                offset = args.get("offset", 0)
                return rows[offset:offset + args.get("limit", 100)]
            # 按是否带 is_active=true 区分返回，验证候选过滤
            key = "get_rule_templates_active" if args.get("is_active") is True else "get_rule_templates"
            return self.data[key]
        if name == "get_task_detail":
            details = self.data.get("task_details") or {}
            return details.get(str(args.get("task_id")), {"subtasks": [], "notes": []})
        if name in _ECHO_TOOLS:
            return dict(args)
        if name == "create_task":
            # 回显创建参数并给出固定 id 与 24h 后的 expires_at（服务端语义）
            return {
                "id": "task-0001",
                "title": args.get("title"),
                "description": args.get("description"),
                "category_id": args.get("category_id"),
                "status": args.get("status"),
                "source": args.get("source"),
                "expires_at": "2026-09-21T09:00:00+00:00",
            }
        if name == "create_subtasks":
            return args.get("subtasks", [])
        result = self.data.get(name)
        if name in ("get_tasks_changed_since", "get_summary_executions"):
            offset = args.get("offset", 0)
            return (result or [])[offset:offset + args.get("limit", 200)]
        return result


def parse_sse(text: str) -> list[tuple[str, dict[str, Any]]]:
    """解析 SSE 文本为 [(事件名, data)]；data 必须都是合法 JSON。"""
    events: list[tuple[str, dict[str, Any]]] = []
    for block in text.split("\n\n"):
        block = block.strip()
        if not block:
            continue
        event_name = None
        data_lines: list[str] = []
        for line in block.split("\n"):
            if line.startswith("event:"):
                event_name = line[len("event:") :].strip()
            elif line.startswith("data:"):
                data_lines.append(line[len("data:") :].strip())
        assert event_name is not None, f"SSE 帧缺少 event 行：{block!r}"
        events.append((event_name, json.loads("".join(data_lines))))
    return events


async def post_chat(message: Any) -> Any:
    """向 /api/chat 发起请求（message 传字符串；传 _Missing 表示省略字段）。"""
    body = {} if message is _Missing else {"message": message}
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.post("/api/chat", json=body)


class _Missing:
    """标记：请求体中不带 message 字段。"""


MISSING_MESSAGE = _Missing


@pytest.fixture
def env(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    """组合夹具：FakeMcp 已挂到 decomposition，可按脚本安装桩 LLM。"""
    fake = FakeMcp()
    monkeypatch.setattr("src.services.decomposition.call_tool", fake)
    state: dict[str, ScriptedTransport] = {}

    def install(scripts: dict[str, list[Any]], delays: dict[str, float] | None = None) -> ScriptedTransport:
        stub = ScriptedTransport(scripts, delays)
        state["stub"] = stub
        monkeypatch.setattr(
            "src.routes.chat._build_llm", lambda: LLMClient(transport=stub)
        )
        return stub

    return SimpleNamespace(fake=fake, install=install)


# ---------------------------------------------------------------------------
# 组 9 共享构造器（test_summary_learn / test_template_writeback /
# test_manual_edit_priority 三份测试共用，惯例 from conftest import）
# ---------------------------------------------------------------------------

# 运行日（Asia/Shanghai）：手动编辑版本行是否落在当天以此为基准
RUN_DAY = date(2026, 9, 20)
# 既有模板的常见步骤基线
SUMMARY_STEPS = ["确定主题", "制作PPT", "彩排"]


def summary_template(
    steps: list[str] | None = None,
    *,
    template_id: str = "tpl-mkt",
    count: int = 4,
    notes: str = "",
) -> dict[str, Any]:
    """构造 flatten_template 之后的模板视图（采集模块产出形状）。"""
    return {
        "id": template_id,
        "name": "市场推广拆解模板",
        "version": 3,
        "category_id": "cat-mkt",
        "common_steps": list(SUMMARY_STEPS if steps is None else steps),
        "estimated_subtask_count": count,
        "notes": notes,
    }


def summary_group(
    template: dict[str, Any] | None = None,
    *,
    category_id: str | None = "cat-mkt",
    category_name: str = "市场推广",
    task_count: int = 2,
    added: list[str] | None = None,
    deleted: list[str] | None = None,
    current: list[str] | None = None,
) -> CategoryGroup:
    """构造一个分类的学习输入（默认 2 条任务都新增「邀请媒体」）。"""
    return CategoryGroup(
        category_id=category_id,
        category_name=category_name,
        template=template,
        tasks=[
            TaskDiff(
                task_id=f"t-{index}",
                title=f"任务{index}",
                added_steps=added if added is not None else ["邀请媒体"],
                deleted_steps=deleted or [],
                ai_steps=list(SUMMARY_STEPS),
                current_steps=current if current is not None else [*SUMMARY_STEPS, "邀请媒体"],
            )
            for index in range(task_count)
        ],
    )


def summary_payload(updates: list[dict[str, Any]], new_notes: str = "") -> dict[str, Any]:
    """LLM 输出形状：{template_updates, new_notes}。"""
    return {"template_updates": updates, "new_notes": new_notes}


@pytest.fixture
def summary_env(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    """组 9 夹具：FakeMcp 挂到总结链路全部模块，桩 LLM 挂到 summary_learn。"""
    fake = FakeMcp()
    patched: list[str] = []
    for module_name in _SUMMARY_MODULES:
        try:
            module = importlib.import_module(module_name)
        except ModuleNotFoundError:  # 该模块尚未实现时跳过
            continue
        if hasattr(module, "call_tool"):
            monkeypatch.setattr(module, "call_tool", fake)
            patched.append(module_name)

    state: dict[str, ScriptedTransport] = {}

    def install(scripts: dict[str, list[Any]], delays: dict[str, float] | None = None) -> ScriptedTransport:
        stub = ScriptedTransport(scripts, delays)
        state["stub"] = stub
        learn = importlib.import_module("src.services.summary_learn")
        monkeypatch.setattr(learn, "_build_llm", lambda: LLMClient(transport=stub))
        return stub

    return SimpleNamespace(fake=fake, install=install, patched=patched)
