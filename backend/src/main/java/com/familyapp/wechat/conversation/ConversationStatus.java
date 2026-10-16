package com.familyapp.wechat.conversation;

/** 微信任务会话状态；与 V15 chk_wtc_status 保持一致。 */
public enum ConversationStatus {
    ACTIVE,
    AWAITING_CONFIRMATION,
    PAUSED,
    CONFIRMED,
    CANCELLED,
    EXPIRED;

    public boolean isRecoverable() {
        return this == ACTIVE || this == AWAITING_CONFIRMATION || this == PAUSED;
    }
}

