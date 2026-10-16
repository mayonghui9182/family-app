# Proposal: agent-service 每日总结 + 对比学习 + ❌2 学习信号

## 目标
- 每日 22:00（Asia/Shanghai）按家庭跑总结：分布式锁防重、分类级 60s 超时容错
- diff_engine 三态学习（ADD≥3/MODIFY≥2/REMOVE≥3/notes≥3）自动生成模板新版本
- ❌2 补项：近 7 天同分类 ≥5 次取消 AI 提案 → change_summary 追加中文警告
- 版本保留策略：is_important 永久 / 普通 30 天 / 每分类最少保留 3 个
- A-2 手动触发（OWNER/ADULT，CHILD→40302）
- V7 迁移：summary_execution_log.status 允许 'running'（锁占位）
- 后端新增 C-19 claim / C-20 finish / C-21 change-summary 追加 端点

## 非目标
- 不做总结内容的前端展示（Change 6）
- 不做跨家庭聚合分析
- LLM 总结建议文本仅作 tips 附加，不改写 diff 的结构化学习结果

## 规约
见 specs/summary-diff-engine/spec.md
