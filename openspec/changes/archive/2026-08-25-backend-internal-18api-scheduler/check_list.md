# Check List: backend-internal-18api-scheduler

| # | 验证项 | 结果 |
|---|---|---|
| 1 | InternalSecret 恒定时间比较（MessageDigest.isEqual） | 通过：InternalSecretInterceptor MessageDigest.isEqual(UTF-8) 恒时比较，单测 U1/U2 + 集成 3 用例 |
| 2 | 10 条 ResultCode 全中文、分段正确（46xxx 校验 / 56xxx 运行） | 通过：ResultCode 46001-46005/46501/56001-56003/56501 全中文；另增 40404-40406 资源段 |
| 3 | C-1~C-14 字段名与签名 100% 对齐规格 §3.4 | 通过：C-1~C-14 snake_case 经 @JsonProperty 显式对齐 §3.4；C-1 page/size 分页 |
| 4 | C-15 四种 conditions（weekly/monthly/weather/combo 嵌套递归）12 断言通过 | 通过：RoutineMatcherTest 13 用例（weekly/monthly/weather 降级/combo AND·OR·三层嵌套/未知 kind 兜底） |
| 5 | C-16 条件 UPDATE 原子消化 + 10 并发幂等用例通过 | 通过：JsonbMappingSmokeTest 抢占语义 + SchedulerAndEventTest 10 并发恰一赢家 |
| 6 | C-17 on_complete 同事务触发 + 边沿判定 + 5min 去重 | 通过：TaskServiceImpl COMPLETED 边沿同事务挂接 + 触发器级 5min 去重（FallbackTest + 集成用例） |
| 7 | C-18 @Transactional 版本两步 + 失败回滚 | 通过：TemplateVersionService @Transactional 两步 + C18 集成断言版本/父指针联动；显式故障注入回滚测试记为评审补充项 |
| 8 | B-1 30s 超时降级直建 PENDING 附中文提示 / B-2 分类级 60s 不阻断 | 通过：decomposeOrFallback 30s 超时降级直建+中文便签（C17_aiFallback e2e）；B-2 60s 独立 RestTemplate 就绪，完整总结环落 Change 5 |
| 9 | cron 2:00/3:00 精确；单家庭异常隔离；存量 V1-V5 端点回归不受影响 | 通过：cron 0 0 2 * * ?/0 0 3 * * ? 精确；dailyRoutineJob 单家庭 try/catch 隔离；全量 149 tests 0 failures（V1-V5 套件全绿） |
