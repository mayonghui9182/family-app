# Spec: 每日总结 + 对比学习

## ADDED Requirements

### C-19 POST /api/internal/agent/summary-log/claim
- query: family_id, force(默认 false)；body: {trigger_source: auto|manual}
- 200 → {acquired: true, log_id} 或 {acquired: false, log_id: 已存在行 id|null}
- 同日已有行且 force=false → acquired=false；force=true → 删同 UTC 日行后重插
- 依赖 uk_sel_family_run_date 唯一冲突（PG 真机并发互斥）

### C-20 PUT /api/internal/agent/summary-log/{id}
- body: {status: success|failed, categories_updated: [..], failed_categories: [{category_id, reason}]}
- 仅 running 行可收尾；行不属于该家庭 → 40404；不存在 → 40404

### C-21 POST /api/internal/agent/templates/{id}/change-summary
- body: {append: 非空中文字符串}；追加到最新版 change_summary（\n 连接）
- 模板不属于该家庭 → 40404；无版本行 → 46503

### C-22 POST /api/internal/agent/template-versions/cleanup
- 版本保留策略：is_important=false 且 created_at < now-30d 且非每模板最新 3 条 → DELETE
- 返回 {deleted: N}

### C-23 GET /api/internal/agent/active-families
- 返回未软删家庭 ID 升序数组（22:00 调度批源；agent_reader 无 families.deleted_at 权限故走后端）

### C-24 GET /api/internal/agent/templates/{id}/versions
- query: family_id, limit（默认 10，上限 50）；模板不属于该家庭 → 40404
- 返回 {template_id, versions: [{version, content, change_summary, is_important, created_at}]}，最新版在前
- 用途：diff 的 AI 原始基线回读 + ❌2 当日幂等判定（rule_template_versions 无列级授权，D10）

### ❌2 cancelled-draft 信号
- 总结首步：近 7 天 tasks WHERE status=CANCELLED AND source='AGENT' GROUP BY category_id HAVING COUNT(*)>=5
- 命中分类 → C-21 追加中文警告（含当日日期、次数与 3 条自查建议）
- 幂等：追加前经 C-24 读最新版 change_summary，已含当日「【系统自动警告·YYYY-MM-DD】」标记则跳过

### A-2 POST /api/v1/summary/trigger（D7：前缀对齐 A 组定版）
- X-Access-Token + X-Family-Id；CHILD → 40302 中文；同日已跑且非 force → 46502
- OWNER/ADULT → 异步执行，立即返回 {trigger_id, estimated_finish_at, message}

### B-2 POST /api/internal/summary/one-family
- X-Internal-Secret；body {family_id, category_ids?, timeout_per_category_ms?, trigger_source}
- trigger_source 大小写不敏感（'MANUAL' → manual，受 V6 CHECK 约束）；category_ids 空 = 全部分类
- 与 22:00 调度共用同一编排（抢锁 → 分类环 → 收尾），返回
  {completed, skipped, categories_updated, failed_categories, versions_created, versions_cleaned}

### diff_engine 三态
- AI snapshot = is_ai_generated=true 子待办；家人增改不入 snapshot
- ADD≥3 / MODIFY≥2 / REMOVE≥3 / notes 短语≥3 触发；产出新版本（C-18）
- title_norm：NFKC + 去空白 + 去 astral + 小写（幂等）
- MODIFY 判定按「缺席基线步骤的原槽位（sort_order）」配对，文本相似度仅用于排除同义改写（D9）
- 新版本 content 保留模板原有无关键（仅覆写 common_steps / notes / count）
- change_summary 全中文，逐条写明「学到什么动作 + 复现次数 + 阈值」

### 分类级容错
- 单分类 asyncio.timeout(summary_timeout_per_category=60s)；超时 → failed_categories 记录
  中文原因，不阻断其它分类（Q79 降级表）
- LLM 只在新版本真要产出时调用（成本纪律），tips 与 mark_important 附加进 change_summary
