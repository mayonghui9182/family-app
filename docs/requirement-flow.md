# AI 辅助开发最小标准包

本包是五阶段流程的试行实现，用于将需求文档/原型整理成可追溯、可验证的 OpenSpec 变更。

入口：[requirement-flow 技能](../.agents/skills/requirement-flow/SKILL.md)。
配套：[执行约定](../.agents/skills/requirement-flow/references/workflow.md)、[模板](../.agents/skills/requirement-flow/assets/templates.md)、[关卡](../.agents/skills/requirement-flow/references/checklists.md)、[提示词](../.agents/skills/requirement-flow/references/prompts.md)。

## 第一次怎么用

1. 选择一个边界清楚的小需求，准备需求文档、原型（如有）及已有约束，标明版本。
2. 在能加载仓库技能的工具中请求：
   > 使用 requirement-flow，处理【材料路径】。先完成需求整理和 change 拆分，列出待确认问题，暂不开发。
3. AI提交范围、R和问题后，ED确认范围并回答阻塞问题，再按阶段推进。
4. 后续可以请求：
   > 继续【change名称】的详细设计和任务拆解，执行第3阶段覆盖检查。
5. 开发与验收沿用现有 OpenSpec 技能。本包不另建任务进度，不改既有配置。

新技能是否自动出现在当前工具列表取决于客户端重新加载；若尚未出现，可直接要求读取入口文件并执行。无 skill 机制的内网工具使用同一套模板和提示词，由操作员按关卡推进。

## 检查命令

在仓库根目录通过项目规定的 WSL 入口执行：

```bash
python3 .agents/skills/requirement-flow/scripts/validate.py openspec/changes/<change-id> --stage 3
python3 .agents/skills/requirement-flow/scripts/test_validate.py
```

退出码0表示结构检查通过，1表示发现问题，2表示输入或格式无法读取。脚本只读且只检查选定目录，不扫描其他 change。首次用于既有变更时，先按模板适配选定文件；不会自动改写旧需求。

## 首次试跑记录

真实需求尚未选定。本包自测使用临时虚构材料，只验证脚本行为，不构成真实需求试跑或业务验收。
试跑后记录：输入规模、发现的遗漏/无依据新增、返工原因、人工耗时、模板负担；据此删减或改进。

## 本包验证记录（2026-10-14）

- skill-creator quick_validate：通过。
- 12项临时夹具自测：通过，含编号断号、重复编号、遗漏映射、无效依据、技术任务、阶段差异、问题阻塞/解决、废弃需求、待复核、证据缺失及退出码。
- Python语法、Markdown本地链接及行尾空白检查：通过。
- 未执行真实需求试跑，未验证客户端热加载；没有改动应用或部署。

## 多格式材料预处理（2026-10-15）

入口已接入解析脚本，支持DOCX、XLSX、MD、静态HTML；图片保留原图并标记OCR待办。旧DOC/XLS及其他格式报告为未支持，不静默跳过。完整用法与局限见[材料解析](../.agents/skills/requirement-flow/references/parsing.md)。

本仓库隔离环境的运行示例（经项目规定的WSL入口执行）：

```bash
.volumes/requirement-flow-venv/bin/python .agents/skills/requirement-flow/scripts/parse_requirements.py /需求材料目录 --output /新的解析结果目录
```

输出包含原件快照、MD阅读稿、资产、结构化提取结果、出处索引、摘要清单和异常报告。退出码0表示未发现自动检查告警，1表示待复核/未支持，2表示失败。不要把0当作业务验收通过。

## 解析器验证记录（2026-10-15）

- 18项解析行为测试与原12项覆盖检查测试通过；安装副本解析测试和技能格式校验通过。
- 使用5份全虚构混合材料实际运行安装版CLI，无文件失败；返回1准确提示公式、图片及动态原型待复核，所有输出摘要一致。
- 本地样例及证据：.volumes/requirement-flow-demo-20261015/output-final/report.md、verification.json；非真实业务验收。
- 个人技能副本已同步，未改应用或生产，尚未提交Git。
