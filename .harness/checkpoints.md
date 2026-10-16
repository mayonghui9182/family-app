# Harness Checkpoint 句式库 · family-app

> 可以直接使用的控制句式，对应 SOP 各阶段。
> 来源：《AI Agent 的 Harness Engineering》附录 + family-app 场景定制。

---

## 起手收敛（阶段 0）

```
先读 [相关 docs / 模块文档]，不要写代码。
先用你的话复述你理解的目标，告诉我当前模块边界在哪里，以及有哪些疑问。
```

---

## 状态恢复（阶段 1）

```
先读 .handoffs/current.md 恢复任务。
告诉我现在做到哪里了、还剩什么、你建议从哪一段接着推进。
```

---

## 压最小 Spec（阶段 3）

```
把这轮任务压成一份最小 spec，写清目标、范围、约束、暂不处理项。
没有我的批准，不要进入实现。
```

---

## 执行前 Checkpoint（阶段 5）

```
先别改代码。做一次 Checkpoint：
总结当前理解、核心目标、下一步动作、风险和验证方式。
我确认后你再执行。
```

---

## 发现偏航时

```
先停，不要继续展开。
你先复述：这轮阶段性核心目标到底是什么，不要谈总目标。
```

---

## 基于证据验证（阶段 6）

```
不要主观判断是否完成。
去看 [tsc 输出 / 测试结果 / curl 返回 / 日志 / openspec validate 输出] / 实际行为，基于事实再回答。
```

---

## 阶段验收

```
不要把这次最小收敛和全局完成混为一谈。
明确告诉我：这轮完成了什么，还没完成什么，下一轮最小目标是什么。
```

---

## 收尾回写（阶段 7）

```
任务暂停。把这一轮实际做了什么、验证了什么、还剩哪些问题没做，
全部回写到 .handoffs/current.md。
```

---

## 进度确认

```
当前做的是 [模块名] 的第 [N] 步。
请确认：总目标没有变，阶段目标没有变，本轮目标 [Z] 是否还准确？
```

---

## family-app 场景专用

> 项目当前阶段以 OpenSpec 工作流为核心，下列变体针对 OpenSpec + Harness 自身的操作。

### 新增 OpenSpec Change 前

```
先读 openspec/config.yaml 和 .claude/skills/openspec-new-change/SKILL.md，不要直接 openspec new change。
先复述当前 OpenSpec 仓库的 schema（spec-driven）和已存在的 changes，
说明本次 change 的命名（kebab-case）、目标、非目标（non-goals）。
确认范围合理后再创建。
```

### 修改 Harness 自身前

```
先读 .harness/sop.md 和 .harness/checkpoints.md 当前版本，不要直接改。
先复述：本次改动会影响 SOP 的哪个阶段？是否会影响现有 hooks 的执行？
回滚策略是什么（harness 是单一 commit 初始化的，可参考该 commit 回退）。
确认后再动手，且必须同步更新 current.md。
```

### 修改 openspec/config.yaml 前

```
先读 openspec/config.yaml 当前内容（注意大部分是注释模板）。
先复述：本次是要启用 context / rules / operations 中的哪一段？
影响的范围是哪些 changes？是否需要重新 validate 已有的 specs？
确认后再改，并验证 openspec validate 通过。
```

---

*Last updated: 2026-08-13*
