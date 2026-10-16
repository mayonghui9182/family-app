## ADDED Requirements

### Requirement: 本机只读工具
系统 SHALL 仅提供读取计划和读取复盘两个 stdio 工具，禁止写入、账号选择和任意 URL 查询。

#### Scenario: 请求计划
- **WHEN** 用户调用有效日期的计划工具
- **THEN** 适配器仅以 GET 访问已配置后端的本人计划，返回最小记录

#### Scenario: 日期无效
- **WHEN** 参数不符合 ISO 日期格式
- **THEN** 返回中文验证错误，不发送 HTTP 请求

### Requirement: 显式启用及凭据保护
系统 SHALL 默认不启动，仅接受合法本地环境配置，不跟随 HTTP 重定向，不输出凭据或服务错误正文。

#### Scenario: 未启用
- **WHEN** 没有显式启用环境变量
- **THEN** 启动失败且不访问后端

#### Scenario: 权限失败或重定向
- **WHEN** 后端拒绝、超时或返回重定向
- **THEN** 返回无原文的中文失败，且不生成记录或跳转新地址

### Requirement: 最小化输出
系统 SHALL 排除内部账号及来源标识、自由文本反馈与初始快照，并保留不可访问提示与实际复盘基线。

#### Scenario: 来源撤权
- **WHEN** 后端项目仅包含 unavailable=true
- **THEN** 工具仅输出不可访问标记，不恢复历史标题
