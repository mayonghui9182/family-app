"""JSON 硬约束（2 层，设计定版 D2：去 L3 正则兜底）。

L1 = 调用侧 response_format={"type":"json_object"} 约束 LLM 只输出合法 JSON Object；
L2 = json.loads + Pydantic 严格校验。失败 → AIJsonGuardError(56002)。
"""
import json
import unicodedata

from pydantic import TypeAdapter, ValidationError

from ..models.conversation_schemas import (
    ConversationRollingSummary,
    ConversationSummaryRequest,
    ConversationTurnAction,
    ConversationTurnRequest,
    PresentCandidateAction,
)
from ..models.errors import AgentServiceError
from ..models.llm_schemas import LLMDecomposeResponse, LLMInterviewResponse, LLMSummarySuggestion


class AIJsonGuardError(AgentServiceError):
    """AI 返回格式解析失败（56002）。"""

    def __init__(self, code: int, message: str) -> None:
        super().__init__(code, message)


def parse(raw_text: str) -> LLMDecomposeResponse:
    """L2 严格解析 LLM 返回文本 → 结构化响应；失败抛 56002。"""
    try:
        data = json.loads(raw_text)
        return LLMDecomposeResponse.model_validate(data)
    except (json.JSONDecodeError, ValidationError) as exc:
        raise AIJsonGuardError(56002, "AI 返回格式解析失败，请重试") from exc


def parse_interview(raw_text: str) -> LLMInterviewResponse:
    """档案访谈单轮 L2 严格解析；失败抛 56002。"""
    try:
        return LLMInterviewResponse.model_validate(json.loads(raw_text))
    except (json.JSONDecodeError, ValidationError) as exc:
        raise AIJsonGuardError(56002, "AI 返回格式解析失败，请重试") from exc


def parse_summary(raw_text: str) -> LLMSummarySuggestion:
    """总结建议 L2 严格解析（同栈复用；tips 解析失败由调用方降级，不阻断结构学习）。"""
    try:
        return LLMSummarySuggestion.model_validate(json.loads(raw_text))
    except (json.JSONDecodeError, ValidationError) as exc:
        raise AIJsonGuardError(56002, "AI 总结建议格式解析失败，已按无建议继续") from exc


_TURN_ACTION_ADAPTER: TypeAdapter[ConversationTurnAction] = TypeAdapter(ConversationTurnAction)


def parse_conversation_turn(
    raw_text: str,
    request: ConversationTurnRequest,
) -> ConversationTurnAction:
    """解析持续会话动作，并校验全部引用都属于本轮家庭上下文。"""
    try:
        action: ConversationTurnAction = _TURN_ACTION_ADAPTER.validate_python(json.loads(raw_text))
        _validate_turn_references(action, request)
        return action
    except (json.JSONDecodeError, ValidationError, ValueError) as exc:
        raise AIJsonGuardError(56002, "AI 会话返回格式解析失败，请重试") from exc


def _validate_turn_references(
    action: ConversationTurnAction,
    request: ConversationTurnRequest,
) -> None:
    """拒绝模型伪造槽位、分类、成员或家庭经验引用。"""
    allowed_slot_keys = set(request.allowed_slot_keys)
    if not set(action.slot_patch).issubset(allowed_slot_keys):
        raise ValueError("模型返回了未允许的槽位")

    allowed_experiences = {item.experience_id for item in request.experiences}
    for slot in action.slot_patch.values():
        if slot.source == "EXPERIENCE_DEFAULT":
            if slot.experience_id not in allowed_experiences:
                raise ValueError("模型引用了当前家庭之外的经验")
        elif slot.source_message_id != request.message_id:
            raise ValueError("模型槽位来源消息不是当前消息")

    if not isinstance(action, PresentCandidateAction):
        return

    allowed_categories = {item.category_id for item in request.categories}
    allowed_profiles = {item.profile_id for item in request.members}
    for task in action.tasks:
        if task.category_name is not None:
            proposed = unicodedata.normalize("NFKC", task.category_name).lower()
            match = next(
                (category for category in request.categories
                 if " ".join(unicodedata.normalize("NFKC", category.name).split()).lower() == proposed),
                None,
            )
            if match is not None:
                task.category_id = match.category_id
                task.category_name = None
            elif request.family.actor_role == "CHILD":
                raise ValueError("儿童不能建议创建新的分类")
        if task.category_id is not None and task.category_id not in allowed_categories:
            raise ValueError("模型引用了当前家庭之外的分类")
        if (
            task.assignee_profile_id is not None
            and task.assignee_profile_id not in allowed_profiles
        ):
            raise ValueError("模型引用了当前家庭之外的成员档案")

_FORBIDDEN_SUMMARY_AUTHORITY = (
    "用户已确认创建",
    "已授权创建",
    "确认授权",
    "立即创建任务",
    "调用 create_task",
    "create_tasks",
)


def parse_conversation_summary(
    raw_text: str,
    request: ConversationSummaryRequest,
) -> ConversationRollingSummary:
    """解析固定结构摘要，并确保游标和无授权语义。"""
    try:
        summary = ConversationRollingSummary.model_validate(json.loads(raw_text))
        if summary.last_message_id != request.messages[-1].message_id:
            raise ValueError("摘要游标未覆盖到本批最后消息")
        summary_texts = (
            summary.confirmed_facts
            + summary.open_questions
            + summary.user_decisions
            + summary.candidate_overview
        )
        normalized = "\n".join(summary_texts).lower()
        if any(marker.lower() in normalized for marker in _FORBIDDEN_SUMMARY_AUTHORITY):
            raise ValueError("摘要不得产生确认授权或任务写动作")
        return summary
    except (json.JSONDecodeError, ValidationError, ValueError) as exc:
        raise AIJsonGuardError(56002, "AI 会话摘要格式解析失败，请重试") from exc
