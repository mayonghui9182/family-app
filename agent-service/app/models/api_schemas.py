"""对外 API 请求/响应模型（对齐 Change 2 AgentCallbackClient 契约，snake_case）。"""
from pydantic import BaseModel, Field


class ChatStreamRequest(BaseModel):
    """A-1 SSE 请求体。"""

    message: str = Field(min_length=1, max_length=2000)
    # A-1 交互式拆解：传 session_id 时，proposal 存到 session.proposed_tasks（B-6），
    # 不直接写业务表；由用户 D-5 confirm 时再批量写 tasks 表。
    # B-1 缓冲式不传 session_id，保持直接写业务表行为。
    session_id: int | None = None
    # 会话类型提示（TASK_CREATE / PROFILE_INTERVIEW）；以 session context 的
    # session_type 为准，此字段仅作兜底（代理 C-25 原样透传请求体）。
    session_type: str | None = None


class AiDecomposeRequest(BaseModel):
    """B-1 请求体：backend AgentCallbackClient.decompose 发送的 snake_case 字段。"""

    title: str
    description: str | None = None
    category_id: int | None = None
    family_id: int = Field(ge=1)
    fallback: bool = True


class DecomposeResult(BaseModel):
    """B-1 响应体：decomposed=false 时 backend 走降级直建。"""

    decomposed: bool
    task_id: int | None = None
    message: str | None = None


class SummaryOneFamilyRequest(BaseModel):
    """B-2 请求体（Change 5 实装）：backend 侧运维/补跑单家庭总结。

    trigger_source 大小写不敏感（路由内归一化为 V6 CHECK 允许的 auto|manual）；
    category_ids 为空表示全部分类。
    """

    family_id: int = Field(ge=1)
    category_ids: list[int] = Field(default_factory=list)
    trigger_source: str = "MANUAL"
