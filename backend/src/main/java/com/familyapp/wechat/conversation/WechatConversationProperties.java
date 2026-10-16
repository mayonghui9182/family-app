package com.familyapp.wechat.conversation;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 微信持续会话处理参数。
 *
 * <p>入口和 worker 分开控制，便于先接收消息再灰度消费，或停入口后排空队列。</p>
 */
@Validated
@ConfigurationProperties(prefix = "wechat.conversation")
public class WechatConversationProperties {

    private boolean enabled = false;
    private boolean workerEnabled = false;

    @Min(1)
    private int summaryEveryTurns = 6;
    @Min(1000)
    private int summaryContextChars = 6000;
    @Min(1)
    private int rawMessageRetentionDays = 30;
    private boolean retentionEnabled = false;
    @Min(1)
    private int summaryRetentionDays = 180;
    @Min(1)
    @Max(200)
    private int retentionBatchSize = 50;
    public boolean isRetentionEnabled() { return retentionEnabled; }
    public void setRetentionEnabled(boolean value) { retentionEnabled = value; }
    public int getSummaryRetentionDays() { return summaryRetentionDays; }
    public void setSummaryRetentionDays(int value) { summaryRetentionDays = value; }
    public int getRetentionBatchSize() { return retentionBatchSize; }
    public void setRetentionBatchSize(int value) { retentionBatchSize = value; }
    public int getSummaryEveryTurns() { return summaryEveryTurns; }
    public void setSummaryEveryTurns(int value) { summaryEveryTurns = value; }
    public int getSummaryContextChars() { return summaryContextChars; }
    public void setSummaryContextChars(int value) { summaryContextChars = value; }
    public int getRawMessageRetentionDays() { return rawMessageRetentionDays; }
    public void setRawMessageRetentionDays(int value) { rawMessageRetentionDays = value; }

    @Min(100)
    private long progressFastThresholdMs = 2500;

    @Min(2)
    @Max(3)
    private int maxReplySegments = 3;

    public long getProgressFastThresholdMs() { return progressFastThresholdMs; }
    public void setProgressFastThresholdMs(long value) { progressFastThresholdMs = value; }
    public int getMaxReplySegments() { return maxReplySegments; }
    public void setMaxReplySegments(int value) { maxReplySegments = value; }

    @Min(100)
    private long pollFixedDelayMs = 1000;

    @PositiveOrZero
    private long pollInitialDelayMs = 15000;

    @Min(5)
    private int leaseSeconds = 120;

    @Min(1)
    @Max(720)
    private int ttlHours = 24;

    @Min(1)
    @Max(20)
    private int maxAttempts = 5;

    @Min(100)
    private long retryInitialDelayMs = 1000;

    @Min(100)
    private long retryMaxDelayMs = 60000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isWorkerEnabled() {
        return workerEnabled;
    }

    public void setWorkerEnabled(boolean workerEnabled) {
        this.workerEnabled = workerEnabled;
    }

    public long getPollFixedDelayMs() {
        return pollFixedDelayMs;
    }

    public void setPollFixedDelayMs(long pollFixedDelayMs) {
        this.pollFixedDelayMs = pollFixedDelayMs;
    }

    public long getPollInitialDelayMs() {
        return pollInitialDelayMs;
    }

    public void setPollInitialDelayMs(long pollInitialDelayMs) {
        this.pollInitialDelayMs = pollInitialDelayMs;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getTtlHours() {
        return ttlHours;
    }

    public void setTtlHours(int ttlHours) {
        this.ttlHours = ttlHours;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getRetryInitialDelayMs() {
        return retryInitialDelayMs;
    }

    public void setRetryInitialDelayMs(long retryInitialDelayMs) {
        this.retryInitialDelayMs = retryInitialDelayMs;
    }

    public long getRetryMaxDelayMs() {
        return retryMaxDelayMs;
    }

    public void setRetryMaxDelayMs(long retryMaxDelayMs) {
        this.retryMaxDelayMs = retryMaxDelayMs;
    }

    @AssertTrue(message = "会话重试最大延迟不得小于初始延迟")
    public boolean isRetryRangeValid() {
        return retryMaxDelayMs >= retryInitialDelayMs;
    }
}
