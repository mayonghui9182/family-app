"""三态对比学习引擎（Change 5 Task 2，对齐 B4 §5.4.2 与 design.md D3/D9）。

输入：模板最新版 content（common_steps / notes = AI 原始快照）+ 同分类近期 COMPLETED
任务的实际子待办与便签样本。输出：DiffReport（差异项 + 复现计数 + 新版本内容 + 中文变更说明）。

配对策略（D9）——按样本聚合复现次数，避免单次偶发改动污染模板：
  ① 精确：AI 行 title_norm 命中模板步骤 → 同动作，不出差异项；
  ② ADD：is_ai_generated=false 的行，其 norm 既不在本样本 AI 集、也不在模板基线，
     且与模板任一步骤相似度 < SLOT_SIMILARITY_FLOOR（否则视为改写的近亲）→ 计 1；
  ③ MODIFY：AI 行未精确命中任何模板步骤，但占了「缺席步骤」的原槽位（sort_order）
     → 就地改写，按 (基线步骤, 新文案) 配对计 1；相似度 ≥KEEP_SIMILARITY 视为同义不动；
  ④ REMOVE：模板步骤在本样本实际集（AI 行 ∪ 家人新增行）中完全缺席 → 计 1。
阈值：ADD ≥3 / MODIFY ≥2 / REMOVE ≥3 / notes 短语 ≥3（plan §全局约束）。
"""
import re
import unicodedata
from collections import Counter
from collections.abc import Iterable
from typing import Any

from ..models.diff_types import DiffItem, DiffReport, NoteMatch, State, TaskSample

ADD_THRESHOLD = 3
MODIFY_THRESHOLD = 2
REMOVE_THRESHOLD = 3
NOTE_THRESHOLD = 3
KEEP_SIMILARITY = 0.8  # ≥0.8 视为同一动作（不产生 MODIFY）
SLOT_SIMILARITY_FLOOR = 0.5  # 低于此值不认作基线步骤的近亲（才允许判为 ADD）
MIN_PHRASE_LEN = 4
MAX_PHRASE_LEN = 24

# 便签分句：中英文标点、空白、引号括号一律作分隔符
_CLAUSE_SPLIT = re.compile(r"[，。；！？、,.;:!?\s　·…\-—\"'“”‘’()（）【】《》]+")
_CJK = re.compile(r"[一-鿿]")
# 纯虚词/功能字：整句只由这些字组成时丢弃（不做词内切分，保留「记得戴橡胶手套」这类完整短语）
_STOP_ONLY_CHARS = set("的了呢吧啊吗哦呀么就是在有和与及或把被给让这那要能没不个之些样很也还又都就")


def title_norm(title: str | None) -> str:
    """标题归一：NFKC 全半角统一 + 去所有空白 + 去 astral 平面（emoji）+ 小写。幂等。"""
    s = unicodedata.normalize("NFKC", title or "")
    s = re.sub(r"\s+", "", s)
    s = "".join(ch for ch in s if ord(ch) < 0x10000)
    return s.lower()


def levenshtein(left: str, right: str) -> int:
    """编辑距离（两行 DP；中文短标题规模极小）。"""
    if left == right:
        return 0
    if not left or not right:
        return max(len(left), len(right))
    prev = list(range(len(right) + 1))
    for i, lc in enumerate(left, start=1):
        cur = [i]
        for j, rc in enumerate(right, start=1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (lc != rc)))
        prev = cur
    return prev[-1]


def similarity(left: str, right: str) -> float:
    """归一化相似度 1 - dist/max_len（同串 → 1.0，一空一非空 → 0.0）。"""
    if not left and not right:
        return 1.0
    longest = max(len(left), len(right))
    if longest == 0:
        return 1.0
    return 1.0 - levenshtein(left, right) / longest


def extract_phrases(notes: Iterable[str], min_len: int = MIN_PHRASE_LEN) -> list[str]:
    """便签 → 候选中文短语（分句 + 去纯虚词句 + 长度窗口），样本内去重保序。"""
    out: list[str] = []
    seen: set[str] = set()
    for note in notes:
        for clause in _CLAUSE_SPLIT.split(note or ""):
            phrase = clause.strip()
            if not (min_len <= len(phrase) <= MAX_PHRASE_LEN):
                continue
            if not _CJK.search(phrase):  # 纯数字/字母/符号句不成短语
                continue
            if all(ch in _STOP_ONLY_CHARS for ch in phrase):  # 全虚词
                continue
            if phrase in seen:
                continue
            seen.add(phrase)
            out.append(phrase)
    return out


def _str_list(content: dict[str, Any], key: str) -> list[str]:
    raw = content.get(key) or []
    if not isinstance(raw, list):
        return []
    return [str(x) for x in raw if str(x).strip()]


class DiffEngine:
    """三态对比引擎（纯计算，不触库；读写由 summarizer/后端承担）。"""

    def build_diff(
        self,
        *,
        family_id: int,
        template_id: int,
        category_id: int,
        template_content: dict[str, Any],
        samples: list[TaskSample],
    ) -> DiffReport:
        """聚合样本 → 三态差异项 + notes 短语 → 新版本内容与中文变更说明。"""
        if not isinstance(family_id, int):  # 与连接器同口径的家庭隔离守卫（绝不允许字符串家庭号）
            raise TypeError("family_id 必须是 int")
        baseline_steps = _str_list(template_content, "common_steps")
        baseline_notes = _str_list(template_content, "notes")
        baseline_norms = [title_norm(s) for s in baseline_steps]
        norm_to_step: dict[str, str] = {}
        for norm, step in zip(baseline_norms, baseline_steps, strict=False):
            norm_to_step.setdefault(norm, step)

        add_counts: Counter[str] = Counter()
        modify_counts: Counter[tuple[str, str]] = Counter()
        remove_counts: Counter[str] = Counter()
        note_counts: Counter[str] = Counter()
        display: dict[str, str] = {}

        for sample in samples:
            ai_norms: set[str] = set()
            fam_norms: set[str] = set()
            ai_rows: list[tuple[str, int, str]] = []  # (norm, slot, raw title)
            for row in sample.subtasks:
                norm = title_norm(row.title)
                if not norm:
                    continue
                display.setdefault(norm, row.title.strip())
                if row.is_ai_generated:
                    ai_norms.add(norm)
                    ai_rows.append((norm, row.sort_order - 1, row.title.strip()))
                else:
                    fam_norms.add(norm)
            actual_norms = ai_norms | fam_norms

            # ② ADD：家人新增且非基线近亲
            for norm in sorted(fam_norms - ai_norms - set(baseline_norms)):
                best = max((similarity(norm, b) for b in baseline_norms if b), default=0.0)
                if best >= SLOT_SIMILARITY_FLOOR:
                    continue
                add_counts[norm] += 1

            # ③ MODIFY：占住「缺席基线步骤」原槽位的改写 AI 行
            absent_slots = [i for i, b in enumerate(baseline_norms) if b and b not in ai_norms]
            used: set[int] = set()
            for norm, slot, _raw in sorted(ai_rows, key=lambda r: (r[1], r[0])):
                if norm in baseline_norms or slot not in absent_slots or slot in used:
                    continue
                base = baseline_norms[slot]
                if similarity(norm, base) >= KEEP_SIMILARITY:
                    continue  # 近义改写，视为同一动作
                modify_counts[(base, norm)] += 1
                used.add(slot)

            # ④ REMOVE：基线步骤在本样本实际集完全缺席
            for base_norm in baseline_norms:
                if base_norm and base_norm not in actual_norms:
                    remove_counts[base_norm] += 1

            for phrase in extract_phrases(sample.notes):
                note_counts[phrase] += 1

        items: list[DiffItem] = []
        for norm, cnt in add_counts.items():
            items.append(
                DiffItem(
                    state=State.ADD,
                    actual_title=display.get(norm, norm),
                    actual_title_norm=norm,
                    recurrence_count=cnt,
                    trigger_threshold_met=cnt >= ADD_THRESHOLD,
                )
            )
        for (base_norm, new_norm), cnt in modify_counts.items():
            items.append(
                DiffItem(
                    state=State.MODIFY,
                    ai_title=norm_to_step.get(base_norm, base_norm),
                    ai_title_norm=base_norm,
                    actual_title=display.get(new_norm, new_norm),
                    actual_title_norm=new_norm,
                    recurrence_count=cnt,
                    trigger_threshold_met=cnt >= MODIFY_THRESHOLD,
                )
            )
        for base_norm, cnt in remove_counts.items():
            items.append(
                DiffItem(
                    state=State.REMOVE,
                    ai_title=norm_to_step.get(base_norm, base_norm),
                    ai_title_norm=base_norm,
                    recurrence_count=cnt,
                    trigger_threshold_met=cnt >= REMOVE_THRESHOLD,
                )
            )
        note_matches = [
            NoteMatch(phrase=p, recurrence_count=c, trigger_threshold_met=c >= NOTE_THRESHOLD)
            for p, c in note_counts.items()
        ]

        new_steps, change_lines = self._apply_steps(baseline_steps, items)
        new_notes, note_lines = self._apply_notes(baseline_notes, note_matches)
        should_emit = any(i.trigger_threshold_met for i in items) or any(n.trigger_threshold_met for n in note_matches)
        summary = "\n".join(change_lines + note_lines) if (change_lines or note_lines) else None
        return DiffReport(
            template_id=template_id,
            category_id=category_id,
            items=items,
            note_matches=note_matches,
            should_emit_new_version=should_emit,
            new_common_steps=new_steps,
            new_template_notes=new_notes,
            change_summary_cn=summary,
        )

    @staticmethod
    def _apply_steps(baseline: list[str], items: list[DiffItem]) -> tuple[list[str], list[str]]:
        """按 MODIFY → REMOVE → ADD 顺序落笔（替换保位置，删除后再追加，顺序稳定可回归）。"""
        steps = list(baseline)
        lines: list[str] = []

        def locate(norm: str | None) -> int:
            for idx, step in enumerate(steps):
                if title_norm(step) == (norm or ""):
                    return idx
            return -1

        for item in items:
            if item.state is not State.MODIFY or not item.trigger_threshold_met:
                continue
            idx = locate(item.ai_title_norm)
            if idx < 0:
                continue
            old = steps[idx]
            new = item.actual_title or old
            steps[idx] = new
            lines.append(
                f"• 替换子步骤：「{old}」→「{new}」"
                f"（家人改写复现 {item.recurrence_count} 次，阈值 ≥{MODIFY_THRESHOLD}）"
            )
        for item in items:
            if item.state is not State.REMOVE or not item.trigger_threshold_met:
                continue
            idx = locate(item.ai_title_norm)
            if idx < 0:
                continue
            old = steps.pop(idx)
            lines.append(
                f"• 删除子步骤：「{old}」"
                f"（家人连续删除复现 {item.recurrence_count} 次，阈值 ≥{REMOVE_THRESHOLD}）"
            )
        for item in items:
            if item.state is not State.ADD or not item.trigger_threshold_met:
                continue
            new = item.actual_title or item.actual_title_norm or ""
            if not new or locate(item.actual_title_norm) >= 0:
                continue
            steps.append(new)
            lines.append(
                f"• 新增家人高频子步骤：「{new}」"
                f"（历史复现 {item.recurrence_count} 次，阈值 ≥{ADD_THRESHOLD}）"
            )

        deduped: list[str] = []
        seen: set[str] = set()
        for step in steps:
            norm = title_norm(step)
            if norm and norm not in seen:
                seen.add(norm)
                deduped.append(step)
        return deduped, lines

    @staticmethod
    def _apply_notes(baseline: list[str], matches: list[NoteMatch]) -> tuple[list[str], list[str]]:
        notes = list(baseline)
        lines: list[str] = []
        existing = {title_norm(n) for n in notes}
        for match in matches:
            if not match.trigger_threshold_met or title_norm(match.phrase) in existing:
                continue
            notes.append(match.phrase)
            existing.add(title_norm(match.phrase))
            lines.append(
                f"• 自动追加模板注意事项：「{match.phrase}」"
                f"（notes 关键词复现 {match.recurrence_count} 次，阈值 ≥{NOTE_THRESHOLD}）"
            )
        return notes, lines
