"""微信公众号任务持续会话的严格输入与模型输出契约。"""

from __future__ import annotations

import unicodedata
from datetime import date, datetime
from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class StrictConversationModel(BaseModel):
    """所有持续会话契约都拒绝未声明字段。"""

    model_config = ConfigDict(extra="forbid")


class FamilyContext(StrictConversationModel):
    """当前家庭的最小必要信息。"""

    family_id: int = Field(ge=1)
    family_name: str = Field(min_length=1, max_length=50)
    actor_user_id: int = Field(ge=1)
    actor_role: Literal["OWNER", "ADULT", "CHILD"]


class CategoryReference(StrictConversationModel):
    """当前家庭内允许引用的分类。"""

    category_id: int = Field(ge=1)
    name: str = Field(min_length=1, max_length=50)


class MemberReference(StrictConversationModel):
    """当前家庭内允许引用的成员档案。"""

    profile_id: int = Field(ge=1)
    display_name: str = Field(min_length=1, max_length=50)
    user_id: int | None = Field(default=None, ge=1)


class RecentMessage(StrictConversationModel):
    """进入模型上下文的最近一条不可变消息。"""

    message_id: int = Field(ge=1)
    direction: Literal["INBOUND", "OUTBOUND", "SYSTEM"]
    content: str = Field(min_length=1, max_length=2000)
    created_at: datetime


class ExperienceContext(StrictConversationModel):
    """当前家庭内可作为默认建议的经验。"""

    experience_id: int = Field(ge=1)
    version: int = Field(ge=1)
    normalized_key: str = Field(min_length=1, max_length=200)
    value_summary: str = Field(min_length=1, max_length=500)


SlotValueData = str | int | float | bool | None | list[str] | list[int]


class SlotValue(StrictConversationModel):
    """带证据来源的槽位值；模型推断和经验默认值都不是用户确认。"""

    value: SlotValueData
    source: Literal["USER_EXPLICIT", "MODEL_INFERRED", "EXPERIENCE_DEFAULT"]
    source_message_id: int | None = Field(default=None, ge=1)
    experience_id: int | None = Field(default=None, ge=1)

    @model_validator(mode="after")
    def validate_source_reference(self) -> Self:
        if self.source == "EXPERIENCE_DEFAULT":
            if self.experience_id is None or self.source_message_id is not None:
                raise ValueError("经验默认槽位必须且只能携带 experience_id")
            return self
        if self.source_message_id is None or self.experience_id is not None:
            raise ValueError("消息来源槽位必须且只能携带 source_message_id")
        return self


class CandidateTask(StrictConversationModel):
    """可展示但尚未授权创建的候选任务。"""

    title: str = Field(min_length=1, max_length=200)
    description: str = Field(default="", max_length=2000)
    due_date: date | None = None
    priority: Literal["HIGH", "MEDIUM", "LOW"] = "MEDIUM"
    category_id: int | None = Field(default=None, ge=1)
    assignee_profile_id: int | None = Field(default=None, ge=1)
    category_name: str | None = Field(default=None, min_length=1, max_length=50)

    @field_validator("category_name", mode="before")
    @classmethod
    def normalize_category_name(cls, value: object) -> object:
        """规范化建议名称，拒绝控制字符；候选阶段不创建分类。"""
        if isinstance(value, str):
            if any(unicodedata.category(char).startswith("C") for char in value):
                raise ValueError("分类名称不能包含控制字符")
            return " ".join(unicodedata.normalize("NFKC", value).split())
        return value

    @model_validator(mode="after")
    def validate_category_choice(self) -> Self:
        if self.category_id is not None and self.category_name is not None:
            raise ValueError("已有分类ID与新分类建议不能同时指定")
        return self


class CandidateSnapshot(StrictConversationModel):
    """当前不可变候选快照的最小上下文。"""

    revision: int = Field(ge=1)
    tasks: list[CandidateTask] = Field(min_length=1, max_length=20)


class ConversationTurnRequest(StrictConversationModel):
    """backend 传入 agent-service 的单轮会话输入。"""

    conversation_id: int = Field(ge=1)
    route_revision: int = Field(ge=0)
    current_revision: int = Field(ge=0)
    message_id: int = Field(ge=1)
    user_text: str = Field(min_length=1, max_length=2000)
    received_at: datetime | None = None
    family: FamilyContext
    allowed_slot_keys: list[str] = Field(default_factory=list, max_length=50)
    slot_state: dict[str, SlotValue] = Field(default_factory=dict, max_length=50)
    current_candidate: CandidateSnapshot | None = None
    rolling_summary: str | None = Field(default=None, max_length=4000)
    recent_messages: list[RecentMessage] = Field(default_factory=list, max_length=16)
    experiences: list[ExperienceContext] = Field(default_factory=list, max_length=5)
    categories: list[CategoryReference] = Field(default_factory=list, max_length=200)
    members: list[MemberReference] = Field(default_factory=list, max_length=200)


class AskClarificationAction(StrictConversationModel):
    """继续追问缺失信息。"""

    action: Literal["ASK_CLARIFICATION"]
    reply: str = Field(min_length=1, max_length=800)
    slot_patch: dict[str, SlotValue] = Field(default_factory=dict, max_length=20)


class PresentCandidateAction(StrictConversationModel):
    """展示一至二十条候选任务，等待 backend 的确定性确认流程。"""

    action: Literal["PRESENT_CANDIDATE"]
    reply: str = Field(min_length=1, max_length=1200)
    tasks: list[CandidateTask] = Field(min_length=1, max_length=20)
    slot_patch: dict[str, SlotValue] = Field(default_factory=dict, max_length=20)


class RefuseOrHelpAction(StrictConversationModel):
    """拒绝越界请求或展示可用帮助。"""

    action: Literal["REFUSE_OR_HELP"]
    reply: str = Field(min_length=1, max_length=800)
    slot_patch: dict[str, SlotValue] = Field(default_factory=dict, max_length=20)


ConversationTurnAction = Annotated[
    AskClarificationAction | PresentCandidateAction | RefuseOrHelpAction,
    Field(discriminator="action"),
]

ShortSummaryText = Annotated[str, Field(min_length=1, max_length=300)]


class ConversationRollingSummary(StrictConversationModel):
    """只用于上下文压缩和展示的固定结构摘要，不携带写授权。"""

    confirmed_facts: list[ShortSummaryText] = Field(default_factory=list, max_length=20)
    open_questions: list[ShortSummaryText] = Field(default_factory=list, max_length=20)
    user_decisions: list[ShortSummaryText] = Field(default_factory=list, max_length=20)
    candidate_overview: list[ShortSummaryText] = Field(default_factory=list, max_length=20)
    last_message_id: int = Field(ge=1)


class ConversationSummaryRequest(StrictConversationModel):
    """上一版摘要与其后连续消息组成的摘要输入。"""

    conversation_id: int = Field(ge=1)
    previous_summary: ConversationRollingSummary | None = None
    messages: list[RecentMessage] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def validate_message_cursor(self) -> Self:
        message_ids = [message.message_id for message in self.messages]
        if message_ids != sorted(set(message_ids)):
            raise ValueError("摘要消息游标必须严格递增且不重复")
        if (
            self.previous_summary is not None
            and self.previous_summary.last_message_id >= message_ids[0]
        ):
            raise ValueError("新摘要消息必须位于上一版摘要游标之后")
        return self
