package com.familyapp.agent;

/**
 * agent_decompose_sessions.session_type 枚举（与 V9.sql CHECK 约束一致）
 * <ul>
 *   <li>TASK_CREATE：任务创建场景拆解（首页/任务页「AI 拆解新增」按钮触发 D-1~D-7）</li>
 *   <li>PROFILE_INTERVIEW：人员档案 AI 访谈（PersonProfileView 页「AI 完善档案」按钮触发）</li>
 * </ul>
 * 两种会话共用同一张 agent_decompose_sessions 表；Task 2 DecomposeSessionService 按 session_type 分流 prompt/逻辑。
 */
public enum DecomposeSessionType {
    TASK_CREATE, PROFILE_INTERVIEW
}
