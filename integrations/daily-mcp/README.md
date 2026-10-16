# 个人每日记录 MCP（本机验证阶段）

仅提供 `read_daily_plan(day)` 和 `read_daily_review(day)`，日期为空时使用北京时间今天。只读取应用内已经生成的记录，不生成、确认、反馈或修改任务。返回标题、重点、状态及基线；不返回自由文本反馈、内部 ID 和初始快照。

## 本机运行

Python 3.12，建议创建独立虚拟环境，安装 `requirements.txt`。协议验证固定 MCP SDK 1.30.0。

在受信任的本机 stdio 客户端配置命令 `python /home/mayh/code/family-app/integrations/daily-mcp/daily_mcp.py`，并通过本地环境提供：

- `DAILY_MCP_ENABLED=true`（缺省不启动）。
- `DAILY_MCP_BACKEND_URL`：实际后端的 HTTPS 根地址或回环 HTTP 根地址，不填路径。不能使用其他主机的明文 HTTP。
- `DAILY_MCP_ACCESS_TOKEN`：本人当前有效登录令牌；只在本地环境配置，不发聊天、不入库、不写入版本控制。应用登录令牌过期后重新登录更新；不得给其他用户共用进程。

这里配置的是本机客户端，不是 ChatGPT 云端的授权方式。本轮没有注册客户端连接、读取真实记录或上传私人数据。后端每日功能需已发布并启用才能读取；未生成记录返回 missing 提示，不替用户写入。

## 验证

`PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s tests -v`，在本目录执行。需要安装 MCP SDK。夹具只绑定随机回环端口、使用虚构账号令牌，无业务数据库或外部模型调用。真实 stdio 验证工具清单和调用，单元测试验证最小化、撤权、认证失败及跳转拒绝。

## 下一阶段：远程 ChatGPT 接入

远程资源服务和独立授权服务已实现，见 [OAuth 运行说明](../../docs/daily-oauth-runbook.md)。已进行本机隔离验证，公网入口仍未启用。

用户后来确认 `app.mayh.net.cn` 经 Cloudflare 公网 HTTPS 可打开，源站为 HTTP，邮箱密码可登录；已有微信对接保留。早期方案见 [远程接入方案](../../docs/chatgpt-remote-access-plan.md)，最新状态以 OAuth 运行说明为准。

1. 确定可用的 HTTPS 域名或账号支持的 Secure MCP Tunnel；隧道的使用仍需用户授权。使用独立远程服务，不直接暴露此 stdio 进程或现有工作写入 MCP。
2. 选择成熟 OAuth 服务；采用授权码 + PKCE S256，发布授权服务器与受保护资源元数据。精确允许 ChatGPT 连接界面给出的 redirect URI；使用 `daily:read` scope，验证 audience、签发者、有效期及撤销。完成账号关联，禁止从工具参数指定应用 user_id。
3. 每次调用核验 OAuth 和应用用户有效性，再调用现有实时权限过滤逻辑；禁止持久保存所有用户共用的应用登录令牌。后端改动、关联持久化与撤权测试另建变更。
4. 先用虚构数据走完整 OAuth、双用户隔离、撤销、过期及工具调用。用户确认数据范围后才授权真实账号接入，不把本机成功视为远程验收完成。

官方依据：[OAuth 认证](https://developers.openai.com/plugins/build/auth)、[Secure MCP Tunnel](https://developers.openai.com/api/docs/guides/secure-mcp-tunnels)。账号能否使用相关功能需在实际连接界面核验。

## 后续每日提醒

现有 08:30/21:00 扫描负责生成，MCP 调用负责读取，两者均不代表主动送达。提醒单独规划：个人选择渠道 → 生成记录后写唯一通知 outbox → 重试和送达状态 → 失败可见。默认关闭，不在日志或锁屏提示中放任务正文。优先应用内提示；微信需单独测试账号与平台权限验收，ChatGPT/Codex 自动任务须核实是否能在定时运行中调用选定连接。不能承诺仅配置 MCP 就会准点通知。

停止本机进程即可回退，无数据库迁移或部署配置改动。
