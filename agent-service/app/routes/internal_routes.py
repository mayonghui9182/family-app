"""B 组内部路由：backend AgentCallbackClient 回调入口。

契约（Change 2 落库为准，勿改字段名）：
B-1 POST /api/internal/ai-decompose   body{title,description,category_id,family_id,fallback}
                                       resp 裸对象 {decomposed, task_id, message}（无信封）
B-2 POST /api/internal/summary/one-family  body{family_id,category_ids,trigger_source}（Change 5 实装）
                                       resp {completed, skipped, status, categories_updated,
                                             failed_categories, versions_created, versions_cleaned, message}
鉴权：X-Internal-Secret 恒定时间比较，失败 → 40102。
"""
from typing import Any

from fastapi import APIRouter, Depends, Request

from ..models.api_schemas import AiDecomposeRequest, DecomposeResult, SummaryOneFamilyRequest
from ..models.conversation_schemas import (
    ConversationRollingSummary,
    ConversationSummaryRequest,
    ConversationTurnAction,
    ConversationTurnRequest,
)
from ..models.errors import InternalSecretError
from ..routes.summary_routes import get_summarizer
from ..services.decomposer import Decomposer
from ..services.summarizer import Summarizer
from ..services.task_conversation_agent import TaskConversationAgent
from ..services.task_conversation_summarizer import TaskConversationSummarizer
from ..settings import get_settings
from ..utils.secrets import constant_time_equals
from .chat_routes import get_decomposer

router = APIRouter(prefix="/api/internal", tags=["internal"])

_task_conversation_singleton: TaskConversationAgent | None = None
_task_conversation_summarizer_singleton: TaskConversationSummarizer | None = None


def get_task_conversation_agent() -> TaskConversationAgent:
    """持续会话 agent 进程级单例；测试通过依赖覆盖替换。"""
    global _task_conversation_singleton
    if _task_conversation_singleton is None:
        _task_conversation_singleton = TaskConversationAgent()
    return _task_conversation_singleton


def get_task_conversation_summarizer() -> TaskConversationSummarizer:
    """持续会话摘要器进程级单例；测试通过依赖覆盖替换。"""
    global _task_conversation_summarizer_singleton
    if _task_conversation_summarizer_singleton is None:
        _task_conversation_summarizer_singleton = TaskConversationSummarizer()
    return _task_conversation_summarizer_singleton


async def require_internal_secret(request: Request) -> None:
    """内部秘钥校验（恒定时间比较防时序侧信道）。"""
    provided = request.headers.get("X-Internal-Secret", "")
    expected = get_settings().internal_api_secret.get_secret_value()
    if not expected:  # 秘钥未配置：拒绝而非放行（防默认空串）
        raise InternalSecretError()
    if not constant_time_equals(provided, expected):
        raise InternalSecretError()


@router.post("/ai-decompose")
async def ai_decompose(
    request: Request,
    body: AiDecomposeRequest,
    decomposer: Decomposer = Depends(get_decomposer),
) -> DecomposeResult:
    """B-1：30s 读超时由调用方控制；任何失败都返回 decomposed=false 由 backend 降级直建。"""
    await require_internal_secret(request)
    decomposed, task_id, message = await decomposer.run_buffered(
        family_id=body.family_id,
        title=body.title,
        description=body.description or "",
        category_hint=body.category_id,
    )
    return DecomposeResult(decomposed=decomposed, task_id=task_id, message=message)


@router.post("/task-conversations/turn")
async def task_conversation_turn(
    request: Request,
    body: ConversationTurnRequest,
    agent: TaskConversationAgent = Depends(get_task_conversation_agent),
) -> ConversationTurnAction:
    """微信公众号持续任务会话单轮入口；只产出候选动作，不执行任务写操作。"""
    await require_internal_secret(request)
    return await agent.turn(body)


@router.post("/task-conversations/summarize")
async def task_conversation_summarize(
    request: Request,
    body: ConversationSummaryRequest,
    summarizer: TaskConversationSummarizer = Depends(
        get_task_conversation_summarizer
    ),
) -> ConversationRollingSummary:
    """持续任务会话滚动摘要入口；摘要不产生确认授权或任务写动作。"""
    await require_internal_secret(request)
    return await summarizer.summarize(body)


@router.post("/summary/one-family")
async def summary_one_family(
    request: Request,
    body: SummaryOneFamilyRequest,
    summarizer: Summarizer = Depends(get_summarizer),
) -> dict[str, Any]:
    """B-2：单家庭总结（backend 侧运维/补跑入口，与 22:00 调度共用同一编排与同一把锁）。

    trigger_source 归一化（'MANUAL'→manual）以匹配 V6 CHECK 约束；
    未抢到当日锁 → completed=false, skipped=true（不报错，调用方按「今日已跑」处理）。
    """
    await require_internal_secret(request)
    source = (body.trigger_source or "auto").lower()
    result = await summarizer.run_family(
        body.family_id, trigger_source=source, only_category_ids=body.category_ids or None
    )
    if result is None:
        return {
            "completed": False,
            "skipped": True,
            "message": "今日已总结过，如需重跑请经 A-2 手动触发并置 force=true",
        }
    return {
        "completed": result["status"] != "failed",
        "skipped": False,
        "message": "本家庭总结已完成",
        **result,
    }
