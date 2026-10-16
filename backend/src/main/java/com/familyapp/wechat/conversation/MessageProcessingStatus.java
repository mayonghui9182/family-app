package com.familyapp.wechat.conversation;

/** 微信会话消息异步处理状态。 */
public enum MessageProcessingStatus {
    PENDING,
    WAITING_FAMILY,
    PROCESSING,
    PROCESSED,
    FAILED,
    SKIPPED
}

