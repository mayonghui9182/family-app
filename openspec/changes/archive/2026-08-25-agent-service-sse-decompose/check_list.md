# Check List: agent-service-sse-decompose

| # | 验证项 | 结果 |
|---|---|---|---|
| 1 | ✅ db_reader SELECT-only grep 无 INSERT/UPDATE/DELETE；decomposer _persist 100% 经 backend_client | 写操作 100% backend_client（无 INSERT/UPDATE/DELETE 走 db_reader） | 待验 |
| 2 | ✅ mypy strict 0 错（28 文件）；公共入口签名 family_id:int；_guard 运行时 TypeError | 7 步编排每步签名 family_id:int；mypy strict 不报错 | 待验 |
| 3 | ✅ L2 backend_client._headers + db_reader._guard TypeError 测试通过 / L1 orchestrator 46000 短路零LLM / L3 SQL filter :family_id / L4 RLS Phase2 注释在 db_reader 文档 | 4 层隔离：db 签名 / backend headers / 编排首行 assert / RLS Phase2 注释 | 待验 |
| 4 | ✅ test_a1_auth_fail_short_circuits_no_llm 断言 llm.calls==[]；test_auth_verifier 五分支全离线 | auth_verifier 失败立刻 SSE error 不调 LLM（mock 断言 llm_client 零调用） | 待验 |
| 5 | ✅ json_guard parse() 仅 json.loads + Pydantic（L1+L2），无 re 模块；test 畸形 JSON→56002 中文 msg | json_guard 解析失败 → 56002 中文 msg（L1+L2，无 L3 正则） | 待验 |
| 6 | ✅ test_normal_flow_event_order + test_a1_stream_happy_path 双断言 classification→reasoning→proposal→done | SSE 事件顺序 exactly：classification → reasoning → proposal → done | 待验 |
| 7 | ✅ test_persist_failure_rollback_cancel_called_once：create_task 1 次 + cancel_task 1 次 → error 56003 | Step 6 写失败原子回滚：backend_client.delete_task 被调用 1 次 | 待验 |
| 8 | ✅ test_secretstr_masked_in_repr_and_logs 断言明文不在 repr；pydantic SecretStr 内置 ********** | DEEPSEEK_API_KEY 等 SecretStr 日志自动脱敏 | 待验 |
| 9 | ✅ ruff 0 错 / mypy strict 0 错（28 文件）/ pytest 40 通过 / coverage 97.49%（门禁 80%） | ruff 0 错 / mypy strict 0 错 / 覆盖率 ≥80% | 待验 |
| 10 | ✅ tests 目录 11 文件：conftest, test_sse_encoding, test_json_guard, test_decomposer_flow(4+1 rollback), test_sse_integration(4), test_stubs, test_isolation(3), test_connectors(5), test_llm_client(3), test_auth_verifier(5), test_misc(8) | tests 目录 6 文件齐全（stub 可，必须存在） | 待验 |

验证时间：2026-08-26。Java 后端回归 152 tests / 0 failures 基线保持。
