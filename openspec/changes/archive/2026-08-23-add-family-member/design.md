# Design: `add-family-member`

**Date**: 2026-08-23
**Change**: add-family-member

本 Design 内容与 `docs/superpowers/specs/2026-08-23-add-family-member-design.md`（用户已审批）完全一致，仅按 openspec 工件结构放一份。

## 1. 数据库模型
完整 4 表 DDL / V4 迁移 SQL / 种子数据 → `docs/superpowers/specs/2026-08-23-add-family-member-design.md` §1.2, §1.3, §1.4, §1.5。

## 2. 鉴权链路
X-Access-Token → Interceptor → ThreadLocal → 条件注册 → 白名单 / 错误路径 → 同上 §2.1, §2.2, §2.3。

## 3. Service API 设计
- auth package（8 类 + 5 DTO）：§3.1
- family package（8 类 + 9 DTO）：§3.2
- tasks 改造：§3.3
- categories 改造：§3.4
- 越权双保险 / 测试条件注册：§3.5

## 4. 错误码 / 异常层级 / 测试范围
§4.1 ResultCode 43 条；§4.2 Exception 2 条；§4.3 测试总览 + §4.4 Docker Smoke 10 步。
