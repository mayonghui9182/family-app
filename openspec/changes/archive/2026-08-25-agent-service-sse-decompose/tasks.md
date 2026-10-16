# Tasks: agent-service-sse-decompose

## Task 1: 工程脚手架 + 配置 + 连接器 + harness 基础
- [x] Step 1: 失败测试 test_sse_encoding(format_sse 断言) + test_json_guard(畸形 JSON → 56002)
- [x] Step 2: pyproject + .env.example + settings.py（9 条配置，敏感字段 SecretStr）
- [x] Step 3: utils/log.py(loguru JSON trace_id) + utils/secrets.py(hmac 恒定比较)
- [x] Step 4: harness 三件套（llm_client SDK 单例+退避 / json_guard 2 层 / sse_stream 节流）
- [x] Step 5: 三 connectors（backend_client / db_reader / weather_client）
- [x] Step 6: 工程约束测试 PASS
- [x] Step 7: Commit

## Task 2: A-1 SSE 7 步编排 + Prompt 5 文件
- [x] Step 1: 失败测试 test_decomposer_flow(4 场景) + test_sse_integration(5 事件顺序)
- [x] Step 2: decomposer.py 7 步编排（每步 family_id:int）
- [x] Step 3: 4 层家庭隔离（编排首行 assert）
- [x] Step 4: prompts/ 5 个 Jinja2 全中文文件
- [x] Step 5: routes/ 三路由（A-1 SSE / A-2 stub / B-1 / B-2 stub）
- [x] Step 6: main.py 挂载 + /healthz + 异常兜底
- [x] Step 7: 4+1 测试 PASS
- [x] Step 8: Commit

## Task 3: 4 层家庭隔离测试 + 质量门
- [x] Step 1: 隔离测试 4 场景（46000 / db SQL 含 family_id / _headers 非 int TypeError / B-1 40102）
- [x] Step 2: ruff 0 错 / mypy strict 0 错 / pytest --cov-fail-under=80
- [x] Step 3: tests 目录 6 文件齐全
- [x] Step 4: check_list 10 项
- [x] Step 5: Commit

完成时间：2026-08-26。commits: 30453ab→eb12451(T1)→75c8def(T2)→3c1f1ee(T3)+doc commit。
