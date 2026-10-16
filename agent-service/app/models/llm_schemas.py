"""LLM 输出严格 JSON 结构（Pydantic）。"""
from pydantic import BaseModel, Field


class SubtaskProposal(BaseModel):
    """单条子待办提案（对齐 backend C-4 批量子待办 + DecomposeSession.toCreateTaskRequest 字段）。

    LLM 拆解时根据任务内容、家庭分类列表、家庭成员档案、历史样本智能生成：
    - category_id: 匹配家庭分类 id，无匹配给 null
    - due_date: yyyy-MM-dd 格式字符串，根据任务紧急度推断
    - assignee_profile_id: 根据成员关系/标签匹配最合适的人，无匹配给 null
    """

    title: str
    description: str = ""
    sort_order: int = 0
    category_id: int | None = None
    due_date: str | None = None
    assignee_profile_id: int | None = None


class LLMDecomposeResponse(BaseModel):
    """LLM 拆解输出的严格 JSON 结构（对齐 backend C-3 创建任务字段）。"""

    title: str
    description: str = ""
    priority: str = "MEDIUM"
    category_id: int | None = None
    subtasks: list[SubtaskProposal] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class LLMInterviewResponse(BaseModel):
    """档案访谈单轮输出（PROFILE_INTERVIEW）：回复 + 累计补丁 + 完成标志。

    - reply: 面向老年用户的简短口语化回复（下一个访谈问题或收尾总结）
    - patch: 到目前为止累计的档案补丁（仅 schema 白名单字段键，覆盖式）
    - remove_keys: 需要从档案中删除的字段键列表
    - profile_id: 目标档案 id（回显，便于校验）
    - done: 访谈是否已完成（核心字段覆盖后才置 true）
    """

    reply: str
    patch: dict[str, object] = Field(default_factory=dict)
    remove_keys: list[str] = Field(default_factory=list)
    profile_id: int | None = None
    done: bool = False


class LLMSummarySuggestion(BaseModel):
    """每日总结建议（Change 5 Task 3）。

    定位（proposal §非目标）：只是 tips 附加，不得改写 diff_engine 的结构化学习结果；
    因此 suggested_*/frequency_removals 仅进 change_summary 文本，不直接落 common_steps。
    """

    additional_tips_cn: str = ""
    mark_important: bool = False
    suggested_common_steps_add: list[str] = Field(default_factory=list)
    frequency_removals: list[str] = Field(default_factory=list)
