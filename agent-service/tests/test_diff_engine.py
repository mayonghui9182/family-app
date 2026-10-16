"""Change 5 Task 2：diff_engine 三态算法（ADD/MODIFY/REMOVE）+ notes 关键词 + 保留策略接线。

语义口径见 design.md D3（复现计数从既有数据算，不引新表）与 D9（三态配对策略）。
假件全部走内存 TaskSample，不触库；写库路径由 C-18/C-22 接线用例覆盖（MockTransport）。
"""
import json

import httpx
import pytest

from app.models.diff_types import DiffItem, DiffReport, NoteMatch, State, SubtaskView, TaskSample
from app.services.diff_engine import DiffEngine, title_norm
from tests.test_connectors import wire_backend

BASE_STEPS = ["收拾客厅", "打扫卧室", "清洁卫生间", "拖地", "消毒门把手"]
TEMPLATE_CONTENT: dict[str, object] = {
    "common_steps": list(BASE_STEPS),
    "count": len(BASE_STEPS),
    "notes": ["大扫除建议周六上午开始"],
}

ENGINE = DiffEngine()


def ai(title: str, sort_order: int) -> SubtaskView:
    """AI 原始（或家人就地改写）子待办：is_ai_generated=true 才进 AI snapshot。"""
    return SubtaskView(title=title, sort_order=sort_order, is_ai_generated=True)


def fam(title: str, sort_order: int = 99) -> SubtaskView:
    """家人自行新增子待办：is_ai_generated=false，绝不污染 AI snapshot。"""
    return SubtaskView(title=title, sort_order=sort_order, is_ai_generated=False)


def diff(samples: list[TaskSample]) -> DiffReport:
    return ENGINE.build_diff(
        family_id=1, template_id=77, category_id=3, template_content=TEMPLATE_CONTENT, samples=samples
    )


def first(report: DiffReport, state: State) -> DiffItem:
    items = [it for it in report.items if it.state is state]
    assert items, f"未找到 {state.value} 项：{[i.state.value for i in report.items]}"
    return items[0]


def baseline_samples(count: int, *, drop_slots: tuple[int, ...] = (), extra: list[SubtaskView] | None = None,
                     notes: tuple[str, ...] = ()) -> list[TaskSample]:
    rows = [ai(step, i + 1) for i, step in enumerate(BASE_STEPS) if (i + 1) not in drop_slots]
    return [
        TaskSample(task_id=100 + i, subtasks=rows + list(extra or []), notes=list(notes)) for i in range(count)
    ]


# ==================== 用例 1：ADD ≥3 ====================


def test_add_recurrence_ge3_appends_new_step() -> None:
    """家人高频新增「擦窗台」复现 3 次（阈值 ≥3）→ 新版本追加该步骤。"""
    report = diff(baseline_samples(3, extra=[fam("擦窗台")]))

    add = first(report, State.ADD)
    assert add.recurrence_count == 3 and add.trigger_threshold_met is True
    assert report.should_emit_new_version is True
    assert "擦窗台" in report.new_common_steps
    assert report.new_common_steps[:5] == BASE_STEPS
    assert "擦窗台" in (report.change_summary_cn or "")
    assert "复现 3 次" in (report.change_summary_cn or "")


def test_add_recurrence_below_threshold_does_not_emit() -> None:
    """只复现 2 次（<3）→ 不产出新版本（学习需稳定信号，避免一次偶发改动污染模板）。"""
    report = diff(baseline_samples(2, extra=[fam("擦窗台")]))

    assert first(report, State.ADD).trigger_threshold_met is False
    assert report.should_emit_new_version is False
    assert report.new_common_steps == BASE_STEPS
    assert report.change_summary_cn is None


# ==================== 用例 2：MODIFY ≥2 ====================


def test_modify_inplace_rewrite_ge2_replaces_step() -> None:
    """家人把 AI 步骤「拖地」就地改成「拖厨房和客厅分开」复现 2 次（阈值 ≥2）→ 替换。"""
    rewritten = [
        ai("收拾客厅", 1), ai("打扫卧室", 2), ai("清洁卫生间", 3),
        ai("拖厨房和客厅分开", 4), ai("倒垃圾", 5),
    ]
    report = diff([TaskSample(task_id=i, subtasks=rewritten, notes=[]) for i in range(2)])

    modify = first(report, State.MODIFY)
    assert modify.recurrence_count == 2 and modify.trigger_threshold_met is True
    assert report.should_emit_new_version is True
    assert "拖厨房和客厅分开" in report.new_common_steps
    assert "拖地" not in report.new_common_steps
    assert "拖地" in (report.change_summary_cn or "")
    assert "替换" in (report.change_summary_cn or "")


def test_modify_below_threshold_keeps_original() -> None:
    """只出现 1 次改写（<2）→ 保持原步骤不动。"""
    rewritten = [
        ai("收拾客厅", 1), ai("打扫卧室", 2), ai("清洁卫生间", 3),
        ai("拖厨房和客厅分开", 4), ai("消毒门把手", 5),
    ]
    report = diff([TaskSample(task_id=1, subtasks=rewritten, notes=[])])

    assert first(report, State.MODIFY).trigger_threshold_met is False
    assert report.new_common_steps == BASE_STEPS
    assert report.should_emit_new_version is False


# ==================== 用例 3：REMOVE ≥3 ====================


def test_remove_deleted_ge3_drops_step() -> None:
    """家人连续 3 次删除 AI 步骤「消毒门把手」（阈值 ≥3）→ 新版本移除该步骤。"""
    report = diff(baseline_samples(3, drop_slots=(5,)))

    remove = first(report, State.REMOVE)
    assert remove.recurrence_count == 3 and remove.trigger_threshold_met is True
    assert "消毒门把手" not in report.new_common_steps
    assert len(report.new_common_steps) == 4
    assert "删除" in (report.change_summary_cn or "")


# ==================== 用例 4：notes 关键词 ≥3 ====================


def test_notes_phrase_ge3_appends_template_notes() -> None:
    """便签短语「记得戴橡胶手套」复现 4 次（≥3）→ 追加到模板注意事项并在 change_summary 说明。"""
    report = diff(baseline_samples(4, notes=("先收垃圾再擦桌子，记得戴橡胶手套",)))

    hit: NoteMatch | None = next((n for n in report.note_matches if n.phrase == "记得戴橡胶手套"), None)
    assert hit is not None and hit.recurrence_count == 4 and hit.trigger_threshold_met is True
    assert report.should_emit_new_version is True
    assert report.new_common_steps == BASE_STEPS
    assert "记得戴橡胶手套" in report.new_template_notes
    assert "大扫除建议周六上午开始" in report.new_template_notes
    assert "notes 关键词" in (report.change_summary_cn or "")
    assert report.template_id == 77 and report.category_id == 3


# ==================== 用例 5：写库接线（C-18 新版本 / C-22 保留策略） ====================


async def test_c22_cleanup_called_on_backend_and_parses_deleted() -> None:
    """版本保留策略落 backend C-22（is_important 永久 / 普通 30 天 / 每模板最少 3；
    语义证据 = Java C22_cleanup_keepsImportantAndYoungest3_deletesOldRest）。
    agent-service 只调用并取回删除条数，绝不自行 DELETE（写 100% 后端）。"""
    seen: dict[str, str | None] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["method"] = request.method
        seen["path"] = request.url.path
        seen["family_id"] = request.url.params.get("family_id")
        seen["secret"] = request.headers.get("X-Internal-Secret")
        return httpx.Response(200, json={"code": 0, "data": {"deleted": 6}})

    client = wire_backend(handler)
    deleted = await client.cleanup_template_versions(7)
    await client.aclose()

    assert deleted == 6
    assert seen["method"] == "POST"
    assert seen["path"] == "/api/internal/agent/template-versions/cleanup"
    assert seen["family_id"] == "7" and seen["secret"] == "itest-secret"


async def test_c18_new_version_payload_is_the_only_write_path() -> None:
    """diff 结果提交只经 C-18（事务内建版本），字段名对齐 backend NewVersionReq。"""
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["path"] = request.url.path
        seen["family_id"] = request.url.params.get("family_id")
        seen["body"] = json.loads(request.content.decode("utf-8"))
        return httpx.Response(200, json={"code": 0, "data": {"new_version": 8}})

    client = wire_backend(handler)
    resp = await client.create_new_template_version(
        7, 77, {"common_steps": ["收拾客厅"], "notes": []}, "【2026-08-26】自动总结·家人行为学习", True
    )
    await client.aclose()

    body = seen["body"]
    assert isinstance(body, dict)
    assert resp["data"]["new_version"] == 8
    assert seen["path"] == "/api/internal/agent/templates/77/new-version"
    assert set(body) == {"content", "change_summary", "is_important"}
    assert body["is_important"] is True


# ==================== 补强：snapshot 纯净性与 title_norm 口径 ====================


def test_family_added_dup_of_ai_step_never_enters_add_and_keeps_snapshot_pure() -> None:
    """check_list 7：is_ai_generated=false 的行不进 AI snapshot。

    家人手工补一条与 AI 步骤同名的子待办 → 不产 ADD；AI 步骤缺席但被家人同名补上
    → 不算 REMOVE（家人实际保留了该动作）。
    """
    sample = TaskSample(
        task_id=1,
        subtasks=[ai("收拾客厅", 1), ai("打扫卧室", 2), ai("清洁卫生间", 3), ai("拖地", 4), fam("消毒门把手")],
        notes=[],
    )

    report = diff([sample])

    assert report.items == []
    assert report.should_emit_new_version is False


def test_no_samples_yields_empty_report() -> None:
    """无历史样本（新家庭）→ 空报告，不产版本（Task 3 分类环据此跳过）。"""
    report = diff([])
    assert report.items == [] and report.note_matches == [] and report.should_emit_new_version is False
    assert report.new_common_steps == BASE_STEPS


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("Ａ Ｂ１ ２", "ab12"),  # 全角→半角（NFKC）+ 去空白 + 小写
        ("擦 窗\t台", "擦窗台"),  # 任意空白剥离
        ("擦窗台 😀", "擦窗台"),  # astral 平面（emoji）剥离
        ("拖地", "拖地"),
        ("", ""),
    ],
)
def test_title_norm_nfkc_and_lowercase(raw: str, expected: str) -> None:
    assert title_norm(raw) == title_norm(title_norm(raw)) == expected.lower()
