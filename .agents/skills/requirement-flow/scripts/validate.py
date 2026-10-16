#!/usr/bin/env python3
"""只读检查选定 change 的结构，不判断业务正确性。"""
import argparse
import re
import sys
from pathlib import Path

RID = r"(?:[A-Z][A-Z0-9]*-\d+-)?R-\d+"
AID = rf"(?:{RID}|TC-\d+|AD-\d+)"


def filled(value):
    return bool(value.strip()) and not re.search(r"<[^>]+>|待填写|TODO|TBD", value, re.I)


def split_refs(value):
    return [part.strip() for part in value.split(",") if part.strip()]


def table(text, key, columns):
    lines = text.splitlines()
    results = []
    for i, line in enumerate(lines):
        if not line.strip().startswith("|"):
            continue
        cells = [x.strip() for x in line.strip().strip("|").split("|")]
        if not cells or cells[0] != key:
            continue
        if cells != columns:
            raise ValueError(f"{key}表列必须为：{' / '.join(columns)}")
        if i + 1 >= len(lines):
            raise ValueError(f"{key}表缺少分隔行")
        separator = [x.strip() for x in lines[i + 1].strip().strip("|").split("|")]
        if len(separator) != len(columns) or not all(re.fullmatch(r":?-{3,}:?", x) for x in separator):
            raise ValueError(f"{key}表分隔行格式错误")
        for row in lines[i + 2:]:
            if not row.strip().startswith("|"):
                break
            values = [x.strip() for x in row.strip().strip("|").split("|")]
            if len(values) != len(columns):
                raise ValueError(f"{key}表行列数错误：{row}")
            results.append(dict(zip(columns, values)))
    if not any(line.strip().startswith("| " + key + " |") or
               (line.strip().startswith("|") and line.strip().strip("|").split("|")[0].strip() == key)
               for line in lines):
        raise ValueError(f"缺少{key}表")
    return results


def check(root, stage):
    errors = []

    def error(message):
        errors.append(message)

    def read(name, required=True):
        path = root / name
        if not path.is_file():
            if required:
                error(f"缺少文件：{name}")
            return ""
        return path.read_text(encoding="utf-8-sig")

    def rows(name, key, cols, required=True):
        content = read(name, required)
        if not content:
            return []
        return table(content, key, cols)

    def index(items, key, pattern):
        result = {}
        for row in items:
            value = row[key]
            if not re.fullmatch(pattern, value):
                error(f"无效编号：{value}")
            if value in result:
                error(f"重复编号：{value}")
            result[value] = row
        return result

    def reference(value, label, allow_na=True):
        if value.startswith("不适用："):
            if not allow_na or not filled(value.removeprefix("不适用：")):
                error(f"{label}不允许不适用或缺少理由")
            return
        if not filled(value) or value.startswith(("未完成", "待确认", "待复核")):
            error(f"{label}尚未落实")
            return
        for ref in split_refs(value):
            path, marker, locator = ref.partition("#")
            if "://" in path or not marker or not locator.strip() or not (root / path).is_file():
                error(f"{label}无效文件引用：{ref}")

    sources = index(rows("sources.md", "来源编号",
        ["来源编号", "材料", "版本", "定位", "确认记录"]), "来源编号", r"S-\d+")
    for sid, row in sources.items():
        if not all(filled(v) for v in row.values()):
            error(f"{sid}来源登记不完整")

    requirements = {}
    for spec in sorted((root / "specs").glob("**/spec.md")):
        content = spec.read_text(encoding="utf-8-sig")
        for block in re.split(r"(?m)^### Requirement:\s*", content)[1:]:
            title = block.splitlines()[0]
            match = re.match(rf"({RID})(?=\s|$)", title)
            if not match:
                error(f"{spec.relative_to(root)}需求标题缺少有效R编号：{title}")
                continue
            rid = match[1]
            if rid in requirements:
                error(f"重复编号：{rid}")
            fields = dict(re.findall(r"(?m)^(来源|状态|验收)：(.*)$", block))
            requirements[rid] = fields
            if fields.get("状态") not in ("有效", "废弃"):
                error(f"{rid}状态必须为有效或废弃")
            source = fields.get("来源", "")
            sid, slash, location = source.partition("/")
            if sid.strip() not in sources or not slash or not filled(location):
                error(f"{rid}来源未登记或缺少具体位置")
            if fields.get("状态") == "有效":
                if not filled(fields.get("验收", "")) or not re.search(r"(?m)^#### Scenario:", block):
                    error(f"{rid}缺少验收标准或Scenario")
    if not requirements:
        error("未找到带R编号的Requirement")
    active = {key for key, val in requirements.items() if val.get("状态") == "有效"}

    questions = index(rows("questions.md", "问题编号",
        ["问题编号", "问题", "来源", "影响", "阻塞阶段", "负责人", "状态", "结论"]),
        "问题编号", r"Q-\d+")
    for qid, row in questions.items():
        if not all(filled(row[k]) for k in ("问题", "来源", "影响", "负责人")):
            error(f"{qid}问题登记不完整")
        if row["状态"] not in ("待确认", "已解决", "不适用"):
            error(f"{qid}问题状态无效")
        if row["阻塞阶段"] not in ("0", "1", "2", "3", "4", "5"):
            error(f"{qid}阻塞阶段须为0–5")
        elif row["状态"] == "待确认" and (
            stage == 5 or 0 < int(row["阻塞阶段"]) <= stage
        ):
            error(f"{qid}仍有当前阶段未解决问题")
        if row["状态"] in ("已解决", "不适用") and not filled(row["结论"]):
            error(f"{qid}缺少处置结论")

    design = read("design.md", stage >= 3)
    technical = {}
    if re.search(r"\|\s*依据编号\s*\|", design):
        technical = index(table(design, "依据编号", ["依据编号", "内容", "来源", "确认记录"]),
                          "依据编号", r"(?:TC|AD)-\d+")
        for aid, row in technical.items():
            if not all(filled(v) for v in row.values()):
                error(f"{aid}技术依据不完整")

    task_text = read("tasks.md", stage >= 3)
    tasks = {}
    for line in task_text.splitlines():
        if not re.match(r"^\s*-\s+\[[ xX]\]", line):
            continue
        match = re.match(r"^\s*-\s+\[([ xX])\]\s+(\d+(?:\.\d+)+)\s+(.+?)\s+\[依据：([^\]]+)\]\s*$", line)
        if not match:
            error(f"任务格式或依据缺失：{line.strip()}")
            continue
        done, tid, description, basis = match.groups()
        if tid in tasks:
            error(f"重复任务编号：{tid}")
        refs = split_refs(basis)
        tasks[tid] = (done.lower() == "x", refs)
        if not filled(description) or not refs:
            error(f"{tid}任务内容或依据为空")
        for ref in refs:
            if not re.fullmatch(AID, ref) or ref not in active | technical.keys():
                error(f"{tid}无效或已废弃依据：{ref}")
        if stage == 5 and done.lower() != "x":
            error(f"{tid}任务尚未完成")
    if stage >= 3 and not tasks:
        error("当前阶段没有有效任务")

    coverage_text = read("coverage.md")
    coverage = index(table(coverage_text, "R编号",
        ["R编号", "架构", "设计", "任务", "验证项", "实现", "证据"]),
        "R编号", RID) if coverage_text else {}
    for rid in active - coverage.keys():
        error(f"{rid}缺少覆盖行")
    for rid in coverage.keys() - active:
        error(f"{rid}覆盖行引用未知或废弃需求")
    for rid, row in coverage.items():
        if stage >= 2:
            reference(row["架构"], f"{rid}架构")
        if stage >= 3:
            reference(row["设计"], f"{rid}设计")
            reference(row["验证项"], f"{rid}验证项", False)
            mapped = split_refs(row["任务"])
            if not mapped:
                error(f"{rid}无任务映射")
            for tid in mapped:
                if tid not in tasks or rid not in tasks[tid][1]:
                    error(f"{rid}任务映射无效或不双向：{tid}")
            if stage >= 4 and (stage == 5 or any(tasks.get(t, (False, []))[0] for t in mapped)):
                reference(row["实现"], f"{rid}实现")
                reference(row["证据"], f"{rid}证据", False)
    if stage >= 3:
        for tid, (_, refs) in tasks.items():
            for rid in set(refs) & active:
                if rid not in coverage or tid not in split_refs(coverage[rid]["任务"]):
                    error(f"{tid}未反向映射到{rid}")

    tech_rows = {}
    if re.search(r"\|\s*任务编号\s*\|", coverage_text):
        tech_rows = index(table(coverage_text, "任务编号",
            ["任务编号", "依据", "验证项", "实现", "证据"]), "任务编号", r"\d+(?:\.\d+)+")
    for tid, row in tech_rows.items():
        expected = set(tasks.get(tid, (False, []))[1]) & technical.keys()
        if tid not in tasks or not expected or set(split_refs(row["依据"])) != expected:
            error(f"{tid}技术映射依据不一致")
        if stage >= 3:
            reference(row["验证项"], f"{tid}技术验证项", False)
        if stage >= 4 and (stage == 5 or tasks.get(tid, (False, []))[0]):
            reference(row["实现"], f"{tid}技术实现")
            reference(row["证据"], f"{tid}技术证据", False)
    if stage >= 3:
        for tid, (_, refs) in tasks.items():
            if set(refs) & technical.keys() and tid not in tech_rows:
                error(f"{tid}缺少技术任务映射")
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("change", type=Path)
    parser.add_argument("--stage", type=int, choices=range(1, 6), required=True)
    args = parser.parse_args()
    if not args.change.is_dir():
        print("错误：change目录不存在", file=sys.stderr)
        return 2
    try:
        errors = check(args.change.resolve(), args.stage)
    except (OSError, UnicodeError, ValueError) as exc:
        print(f"输入格式错误：{exc}", file=sys.stderr)
        return 2
    for error in errors:
        print(f"- {error}")
    print(f"结构检查：{len(errors)}项问题；不代表业务验收或人工评审通过。")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
