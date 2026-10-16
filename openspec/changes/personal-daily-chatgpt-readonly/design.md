## Context

后端 GET /api/daily/plans/{date} 与 reviews/{date} 已按登录用户读取，实时过滤撤权来源。本机 MCP 用于协议验证，不代表完成 ChatGPT 云端连接。

## Goals / Non-Goals

独立验证只读契约，避免复用工作 MCP 写入工具；不部署远程服务或自制 OAuth。

## Decisions

- 独立 stdio 进程，仅两个工具；不监听网络、不复用现有工作 MCP。
- 本地环境 DAILY_MCP_ENABLED=true 才可启动；BACKEND_URL 仅允许显式回环 HTTP 或 HTTPS 根地址，禁止 URL 凭据、查询和路径。密钥从 DAILY_MCP_ACCESS_TOKEN 环境读取，不在工具参数或结果暴露。
- 标准库 HTTP 客户端禁用代理及重定向，5 秒超时、1 MiB 响应上限，错误统一中文且无服务原文。
- 返回允许字段，排除反馈、初始快照和内部来源 ID；复盘反馈含自由文本，首阶段不暴露。任务标题作为不可信数据。
- 日期必须 ISO YYYY-MM-DD；缺省日期使用 Asia/Shanghai。无缺失补跑、无写入。
- 远程阶段优先成熟 OAuth 提供商，授权码 + PKCE、每日只读 scope、audience/expiry/revocation 验证；需确定域名或受支持隧道与账号能力后实施。

## Risks / Trade-offs

本机令牌仍具有原应用权限 → 不暴露网络且只供本人受信任本机客户端；远程阶段不能直接复用此令牌。客户端可能外发工具数据 → 当前不配置客户端连接，不使用真实数据测试。

## Migration Plan

仅新增文件，无迁移；停止进程即可回退。远程接入和外部通知需单独发布与数据发送授权。
