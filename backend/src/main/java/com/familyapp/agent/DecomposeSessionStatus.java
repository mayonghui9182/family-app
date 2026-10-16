package com.familyapp.agent;

/**
 * agent_decompose_sessions.status 枚举（与 V9.sql CHECK 约束一致）
 * <ul>
 *   <li>ACTIVE：D-1~D-4 进行中（AI 沟通/拆解阶段）</li>
 *   <li>CONFIRMED：D-5 用户确认终态（已写 tasks + 更新 person_profiles.profile_data patch）</li>
 *   <li>CANCELLED：D-6 用户放弃终态（仅写 extracted_profile_patch 可选，不写任务）</li>
 *   <li>ARCHIVED：D-7 系统归档终态（用户再次确认后会话锁定，消息不可追加）</li>
 * </ul>
 * B-5/B-6 端点仅 ACTIVE 状态允许写（终态后再写由 DecomposeSessionController 拦截，报 50311）。
 */
public enum DecomposeSessionStatus {
    ACTIVE, CONFIRMED, CANCELLED, ARCHIVED
}
