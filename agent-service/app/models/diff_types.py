"""三态 diff 数据结构（Change 5 Task 2）。

语义：以「模板最新版 common_steps」为 AI 原始快照基线，与同分类近期 COMPLETED 任务的
实际子待办逐条比对，产出家人行为学习差异报告（diff_engine.D9 配对策略）。
"""
from enum import StrEnum

from pydantic import BaseModel, Field


class State(StrEnum):
    """家人行为三态（KEEP_SAME 不产出差异项，故不入枚举）。"""

    ADD = "ADD"  # 家人新增且 AI 快照无 → 复现 ≥3 追加
    MODIFY = "MODIFY"  # 家人就地改写 AI 步骤（同槽但文案不同）→ 复现 ≥2 替换
    REMOVE = "REMOVE"  # 家人删除 AI 步骤（实际集中完全缺席）→ 复现 ≥3 移除


class SubtaskView(BaseModel):
    """子待办视图（is_ai_generated=true 才是 AI snapshot 成员）。"""

    title: str
    description: str | None = None
    sort_order: int = 1
    is_ai_generated: bool = False


class TaskSample(BaseModel):
    """同分类一条近期已完成任务的实际形态（子待办 + 便签）。"""

    task_id: int
    subtasks: list[SubtaskView] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class DiffItem(BaseModel):
    """单个三态差异项。norm 字段用于匹配，title 字段用于写回模板（保留家人原文）。"""

    state: State
    ai_title: str | None = None
    ai_title_norm: str | None = None
    actual_title: str | None = None
    actual_title_norm: str | None = None
    recurrence_count: int = 0
    trigger_threshold_met: bool = False


class NoteMatch(BaseModel):
    """便签中文短语复现。"""

    phrase: str
    recurrence_count: int = 0
    trigger_threshold_met: bool = False


class DiffReport(BaseModel):
    """一次（家庭×模板）对比学习的完整结论；should_emit_new_version 决定是否走 C-18。"""

    template_id: int
    category_id: int
    items: list[DiffItem] = Field(default_factory=list)
    note_matches: list[NoteMatch] = Field(default_factory=list)
    should_emit_new_version: bool = False
    new_common_steps: list[str] = Field(default_factory=list)
    new_template_notes: list[str] = Field(default_factory=list)
    change_summary_cn: str | None = None
