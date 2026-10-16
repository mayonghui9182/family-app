#!/usr/bin/env python3
"""使用临时虚构变更验证检查器，不触碰真实需求。"""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("validate.py")
SPEC = importlib.util.spec_from_file_location("validator", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def make_table(headers, rows):
    return "\n".join(["| " + " | ".join(headers) + " |",
                      "| " + " | ".join("---" for _ in headers) + " |"] +
                     ["| " + " | ".join(row) + " |" for row in rows]) + "\n"


class ValidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.write("sources.md", make_table(
            ["来源编号", "材料", "版本", "定位", "确认记录"],
            [["S-001", "虚构需求", "v1", "fixture", "测试确认记录"]]))
        self.write("specs/demo/spec.md", """## ADDED Requirements
### Requirement: R-003 虚构标题
来源：S-001 / 第1节
状态：有效
验收：显示标题
#### Scenario: 查看
- **WHEN** 打开
- **THEN** 显示标题
""")
        self.write("questions.md", make_table(
            ["问题编号", "问题", "来源", "影响", "阻塞阶段", "负责人", "状态", "结论"], []))
        self.write("design.md", "# 设计\n")
        self.write("tasks.md", "- [x] 1.1 实现标题 [依据：R-003]\n")
        self.write("verification.md", "# 虚构验证记录\n")
        self.write("impl.txt", "虚构实现\n")
        self.coverage()

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")

    def replace(self, name, old, new):
        path = self.root / name
        path.write_text(path.read_text().replace(old, new), encoding="utf-8")

    def coverage(self):
        self.write("coverage.md", make_table(
            ["R编号", "架构", "设计", "任务", "验证项", "实现", "证据"],
            [["R-003", "不适用：复用现有结构", "design.md#设计", "1.1",
              "verification.md#方案", "impl.txt#实现", "verification.md#结果"]]))

    def test_valid_and_number_gap(self):
        self.assertEqual([], MODULE.check(self.root, 5))

    def test_unknown_task_basis(self):
        self.replace("tasks.md", "R-003", "R-099")
        self.assertTrue(any("无效或已废弃依据" in x for x in MODULE.check(self.root, 3)))

    def test_missing_coverage(self):
        self.replace("coverage.md", "R-003", "R-099")
        self.assertTrue(any("缺少覆盖行" in x for x in MODULE.check(self.root, 1)))

    def test_duplicate_requirement(self):
        content = (self.root / "specs/demo/spec.md").read_text()
        self.write("specs/other/spec.md", content)
        self.assertTrue(any("重复编号" in x for x in MODULE.check(self.root, 1)))

    def test_future_stage_can_be_pending(self):
        self.replace("coverage.md", "design.md#设计", "未完成")
        self.assertEqual([], MODULE.check(self.root, 1))
        self.assertTrue(MODULE.check(self.root, 3))

    def test_missing_evidence(self):
        self.replace("coverage.md", "verification.md#结果", "missing.md#结果")
        self.assertTrue(any("无效文件引用" in x for x in MODULE.check(self.root, 4)))

    def test_technical_only_task(self):
        with (self.root / "design.md").open("a") as f:
            f.write(make_table(["依据编号", "内容", "来源", "确认记录"],
                              [["TC-001", "虚构日志约束", "设计讨论", "已确认记录"]]))
        with (self.root / "tasks.md").open("a") as f:
            f.write("- [x] 1.2 记录日志 [依据：TC-001]\n")
        with (self.root / "coverage.md").open("a") as f:
            f.write("\n" + make_table(["任务编号", "依据", "验证项", "实现", "证据"],
                [["1.2", "TC-001", "verification.md#方案", "impl.txt#日志", "verification.md#结果"]]))
        self.assertEqual([], MODULE.check(self.root, 5))

    def test_blocking_question_and_resolution(self):
        self.write("questions.md", make_table(
            ["问题编号", "问题", "来源", "影响", "阻塞阶段", "负责人", "状态", "结论"],
            [["Q-001", "标题长度", "S-001", "页面", "3", "需求负责人", "待确认", ""]]))
        self.assertEqual([], MODULE.check(self.root, 2))
        self.assertTrue(MODULE.check(self.root, 3))
        self.replace("questions.md", "待确认 |  |", "已解决 | 上限20字 |")
        self.assertEqual([], MODULE.check(self.root, 3))

    def test_deprecated_requirement(self):
        self.replace("specs/demo/spec.md", "状态：有效", "状态：废弃")
        self.assertTrue(any("废弃" in x for x in MODULE.check(self.root, 3)))

    def test_unfinished_task_gate(self):
        self.replace("tasks.md", "[x]", "[ ]")
        self.assertEqual([], MODULE.check(self.root, 4))
        self.assertTrue(any("尚未完成" in x for x in MODULE.check(self.root, 5)))

    def test_changed_mapping(self):
        self.replace("coverage.md", "design.md#设计", "待复核：需求变化")
        self.assertTrue(any("尚未落实" in x for x in MODULE.check(self.root, 3)))

    def test_cli_exit_codes(self):
        def run():
            return subprocess.run([sys.executable, str(SCRIPT), str(self.root), "--stage", "5"],
                                  capture_output=True).returncode
        self.assertEqual(0, run())
        self.replace("tasks.md", "R-003", "R-999")
        self.assertEqual(1, run())
        self.write("coverage.md", "| R编号 | 错列 |\n| --- | --- |\n")
        self.assertEqual(2, run())


if __name__ == "__main__":
    unittest.main()
