package com.familyapp.wechat.conversation;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static com.familyapp.wechat.conversation.ConversationStatus.ACTIVE;
import static com.familyapp.wechat.conversation.ConversationStatus.AWAITING_CONFIRMATION;
import static com.familyapp.wechat.conversation.ConversationStatus.CANCELLED;
import static com.familyapp.wechat.conversation.ConversationStatus.CONFIRMED;
import static com.familyapp.wechat.conversation.ConversationStatus.EXPIRED;
import static com.familyapp.wechat.conversation.ConversationStatus.PAUSED;

/**
 * 微信任务会话确定性状态服务。
 *
 * <p>所有读写都同时携带绑定和家庭范围；可恢复态占用 active_slot=1，
 * 终态释放槽位，使同一绑定在同一家庭始终最多只有一个可恢复会话。</p>
 */
@Service
public class WechatConversationStateService {

    private static final Short ACTIVE_SLOT = 1;
    private static final String END_REASON_KEY = "_system_end_reason";

    private final WechatTaskConversationRepository repository;
    private final WechatConversationProperties properties;
    private final WechatFamilyAccessGuard accessGuard;
    private final Clock clock;

    public WechatConversationStateService(
            WechatTaskConversationRepository repository,
            WechatConversationProperties properties,
            WechatFamilyAccessGuard accessGuard,
            Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.accessGuard = accessGuard;
        this.clock = clock;
    }

    /**
     * 打开目标家庭会话；暂停会话未过期则恢复，过期则终止并新建。
     */
    @Transactional
    public WechatTaskConversation openOrResume(
            long wechatBindId,
            long userId,
            long familyId) {
        accessGuard.requireAccess(
                userId,
                familyId,
                WechatFamilyAccessGuard.AccessOperation.RESUME_CONVERSATION);
        return openOrResumeLocked(wechatBindId, userId, familyId, clock.instant());
    }

    /**
     * 切换家庭：原家庭只暂停，目标家庭按暂停状态恢复或新建。
     */
    @Transactional
    public WechatTaskConversation switchFamily(
            long wechatBindId,
            long userId,
            long currentFamilyId,
            long targetFamilyId) {
        accessGuard.requireAccess(
                userId,
                targetFamilyId,
                WechatFamilyAccessGuard.AccessOperation.SELECT_FAMILY);
        Instant now = clock.instant();
        if (currentFamilyId != targetFamilyId) {
            if (accessGuard.findAccess(userId, currentFamilyId).isPresent()) {
                pauseForFamilySwitch(wechatBindId, userId, currentFamilyId, now);
            } else {
                terminateRevokedFamily(
                        wechatBindId,
                        userId,
                        currentFamilyId,
                        now);
            }
        }
        return openOrResumeLocked(wechatBindId, userId, targetFamilyId, now);
    }

    /**
     * “新建任务”终止当前未完成会话，并占用释放后的 active_slot 新建会话。
     */
    @Transactional
    public WechatTaskConversation startNewConversation(
            long wechatBindId,
            long userId,
            long familyId) {
        accessGuard.requireAccess(
                userId,
                familyId,
                WechatFamilyAccessGuard.AccessOperation.ROUTE_MESSAGE);
        Instant now = clock.instant();
        repository.findActiveForUpdate(wechatBindId, familyId)
                .ifPresent(conversation -> {
                    requireUser(conversation, userId);
                    finish(conversation, CANCELLED, "NEW_CONVERSATION", now);
                });
        return createConversation(wechatBindId, userId, familyId, null, now);
    }

    /** 生成候选后进入精确确认态。 */
    @Transactional
    public WechatTaskConversation awaitConfirmation(long conversationId, long familyId) {
        WechatTaskConversation conversation = scoped(conversationId, familyId);
        requireStatus(conversation, ACTIVE);
        conversation.setStatus(AWAITING_CONFIRMATION);
        touch(conversation, clock.instant());
        return repository.save(conversation);
    }

    /** 用户修改需求或补充信息后回到普通活动态。 */
    @Transactional
    public WechatTaskConversation returnToActive(long conversationId, long familyId) {
        WechatTaskConversation conversation = scoped(conversationId, familyId);
        requireStatus(conversation, AWAITING_CONFIRMATION);
        conversation.setStatus(ACTIVE);
        touch(conversation, clock.instant());
        return repository.save(conversation);
    }

    /** 精确确认后进入终态并释放 active_slot。 */
    @Transactional
    public WechatTaskConversation confirm(
            long conversationId,
            long familyId,
            long userId) {
        accessGuard.requireAccess(
                userId,
                familyId,
                WechatFamilyAccessGuard.AccessOperation.CONFIRM_TASK);
        WechatTaskConversation conversation = scoped(conversationId, familyId);
        requireStatus(conversation, AWAITING_CONFIRMATION);
        finish(conversation, CONFIRMED, null, clock.instant());
        return conversation;
    }

    /** 用户取消当前会话，保留审计数据但释放 active_slot。 */
    @Transactional
    public WechatTaskConversation cancel(long conversationId, long familyId) {
        WechatTaskConversation conversation = scoped(conversationId, familyId);
        requireRecoverable(conversation);
        finish(conversation, CANCELLED, "USER_CANCELLED", clock.instant());
        return conversation;
    }

    /**
     * 刷新最后活动时间和空闲截止时间。
     */
    @Transactional
    public WechatTaskConversation recordActivity(long conversationId, long familyId) {
        WechatTaskConversation conversation = scoped(conversationId, familyId);
        requireRecoverable(conversation);
        touch(conversation, clock.instant());
        return repository.save(conversation);
    }

    /**
     * 读取当前家庭历史前重新验证成员和家庭状态。
     */
    @Transactional(readOnly = true)
    public java.util.List<WechatTaskConversation> listHistory(
            long wechatBindId,
            long userId,
            long familyId) {
        accessGuard.requireAccess(
                userId,
                familyId,
                WechatFamilyAccessGuard.AccessOperation.READ_HISTORY);
        return repository.history(familyId, wechatBindId, userId,
                org.springframework.data.domain.PageRequest.of(0, 5));
    }

    /** 从当前家庭历史派生新会话，仅继承用户原文事实，不继承旧候选或确认授权。 */
    @Transactional
    public WechatTaskConversation deriveHistory(long bindId, long userId, long familyId, long parentId) {
        accessGuard.requireAccess(userId, familyId,
                WechatFamilyAccessGuard.AccessOperation.RESUME_CONVERSATION);
        var parent = repository.findByIdAndFamilyIdForUpdate(parentId, familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        requireUser(parent, userId);
        if (!Long.valueOf(bindId).equals(parent.getWechatBindId())
                || (parent.getStatus().isRecoverable() && parent.getStatus() != PAUSED)) {
            throw new WechatFamilyAccessDeniedException();
        }
        Instant now = clock.instant();
        repository.findActiveForUpdate(bindId, familyId).ifPresent(current -> {
            requireUser(current, userId);
            finish(current, CANCELLED, "HISTORY_DERIVED", now);
        });
        var child = createConversation(bindId, userId, familyId, parentId, now);
        child.setRollingSummary(parent.getRollingSummary());
        Map<String, Object> facts = new LinkedHashMap<>();
        for (var entry : parent.getSlotState().entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> value
                    && "USER_EXPLICIT".equals(value.get("source"))
                    && WechatConversationTurnPreparationService.ALLOWED_SLOT_KEYS.contains(entry.getKey())) {
                facts.put(entry.getKey(), new LinkedHashMap<>(value));
            }
        }
        child.setSlotState(facts);
        return repository.save(child);
    }

    /**
     * 当前家庭权限失效时终止活动会话；调用方据返回值要求用户重新选择家庭。
     */
    @Transactional
    public boolean invalidateIfAccessRevoked(
            long wechatBindId,
            long userId,
            long familyId) {
        if (accessGuard.findAccess(userId, familyId).isPresent()) {
            return false;
        }
        terminateRevokedFamily(
                wechatBindId,
                userId,
                familyId,
                clock.instant());
        return true;
    }

    /**
     * 若当前活动槽已经超时则转为 EXPIRED；未超时或不存在时不修改。
     */
    @Transactional
    public Optional<WechatTaskConversation> expireIfIdle(
            long wechatBindId,
            long familyId) {
        Instant now = clock.instant();
        Optional<WechatTaskConversation> current =
                repository.findActiveForUpdate(wechatBindId, familyId);
        if (current.isEmpty() || !hasExpired(current.get(), now)) {
            return Optional.empty();
        }
        finish(current.get(), EXPIRED, "TTL_EXPIRED", now);
        return current;
    }

    private WechatTaskConversation openOrResumeLocked(
            long wechatBindId,
            long userId,
            long familyId,
            Instant now) {
        Optional<WechatTaskConversation> current =
                repository.findActiveForUpdate(wechatBindId, familyId);
        if (current.isEmpty()) {
            return createConversation(wechatBindId, userId, familyId, null, now);
        }

        WechatTaskConversation conversation = current.get();
        requireUser(conversation, userId);
        if (hasExpired(conversation, now)) {
            finish(conversation, EXPIRED, "TTL_EXPIRED", now);
            return createConversation(wechatBindId, userId, familyId, null, now);
        }

        if (conversation.getStatus() == PAUSED) {
            ConversationStatus restored = conversation.getPausedFromStatus();
            if (restored != ACTIVE && restored != AWAITING_CONFIRMATION) {
                throw new IllegalStateException("暂停会话缺少可恢复状态");
            }
            conversation.setStatus(restored);
            conversation.setPausedFromStatus(null);
        } else if (conversation.getStatus() != ACTIVE
                && conversation.getStatus() != AWAITING_CONFIRMATION) {
            throw new IllegalStateException("活动槽包含不可恢复状态");
        }

        touch(conversation, now);
        return repository.save(conversation);
    }

    private void pauseForFamilySwitch(
            long wechatBindId,
            long userId,
            long familyId,
            Instant now) {
        repository.findActiveForUpdate(wechatBindId, familyId)
                .ifPresent(conversation -> {
                    requireUser(conversation, userId);
                    if (hasExpired(conversation, now)) {
                        finish(conversation, EXPIRED, "TTL_EXPIRED", now);
                        return;
                    }
                    if (conversation.getStatus() == ACTIVE
                            || conversation.getStatus() == AWAITING_CONFIRMATION) {
                        conversation.setPausedFromStatus(conversation.getStatus());
                        conversation.setStatus(PAUSED);
                        touch(conversation, now);
                        repository.save(conversation);
                    }
                });
    }

    private void terminateRevokedFamily(
            long wechatBindId,
            long userId,
            long familyId,
            Instant now) {
        repository.findActiveForUpdate(wechatBindId, familyId)
                .filter(conversation -> Long.valueOf(userId)
                        .equals(conversation.getUserId()))
                .ifPresent(conversation -> finish(
                        conversation,
                        CANCELLED,
                        "FAMILY_ACCESS_REVOKED",
                        now));
    }

    private WechatTaskConversation createConversation(
            long wechatBindId,
            long userId,
            long familyId,
            Long parentConversationId,
            Instant now) {
        WechatTaskConversation conversation = new WechatTaskConversation();
        conversation.setWechatBindId(wechatBindId);
        conversation.setUserId(userId);
        conversation.setFamilyId(familyId);
        conversation.setParentConversationId(parentConversationId);
        conversation.setStatus(ACTIVE);
        conversation.setPausedFromStatus(null);
        conversation.setActiveSlot(ACTIVE_SLOT);
        conversation.setCurrentRevision(0);
        conversation.setSlotState(new LinkedHashMap<>());
        touch(conversation, now);
        return repository.save(conversation);
    }

    private WechatTaskConversation scoped(long conversationId, long familyId) {
        return repository.findByIdAndFamilyId(conversationId, familyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "会话不存在或不属于当前家庭"));
    }

    private void requireStatus(
            WechatTaskConversation conversation,
            ConversationStatus expected) {
        if (conversation.getStatus() != expected) {
            throw new IllegalStateException(
                    "会话状态不允许当前操作: " + conversation.getStatus());
        }
    }

    private void requireRecoverable(WechatTaskConversation conversation) {
        if (!conversation.getStatus().isRecoverable()) {
            throw new IllegalStateException(
                    "终态会话不能再次迁移: " + conversation.getStatus());
        }
    }

    private void requireUser(WechatTaskConversation conversation, long userId) {
        if (!Long.valueOf(userId).equals(conversation.getUserId())) {
            throw new IllegalArgumentException("微信绑定用户与会话用户不一致");
        }
    }

    private boolean hasExpired(
            WechatTaskConversation conversation,
            Instant now) {
        return !now.isBefore(conversation.getExpiresAt());
    }

    private void touch(WechatTaskConversation conversation, Instant now) {
        conversation.setLastActivityAt(now);
        conversation.setExpiresAt(now.plus(ttl()));
    }

    private Duration ttl() {
        return Duration.ofHours(properties.getTtlHours());
    }

    private void finish(
            WechatTaskConversation conversation,
            ConversationStatus terminalStatus,
            String endReason,
            Instant now) {
        conversation.setStatus(terminalStatus);
        conversation.setPausedFromStatus(null);
        conversation.setActiveSlot(null);
        conversation.setLastActivityAt(now);
        if (endReason != null) {
            Map<String, Object> state = new LinkedHashMap<>(conversation.getSlotState());
            state.put(END_REASON_KEY, endReason);
            conversation.setSlotState(state);
        }
        repository.saveAndFlush(conversation);
    }
}
