package com.familyapp.wechat.conversation;

/** 客服消息 outbox 投递状态；与 V15 chk_wmo_status 保持一致。 */
public enum OutboxStatus {
    PENDING,
    SENDING,
    SENT,
    CANCELLED,
    FAILED
}

