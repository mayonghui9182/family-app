package com.familyapp.wechat.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** 家庭生命周期清理；禁止用删除微信绑定代替家庭范围内的清理。 */
@Service
public class WechatFamilyDeletionService {
    private final JdbcTemplate jdbc;
    private final WechatChatContextRepository contexts;
    private final WechatTaskConversationRepository conversations;
    private final EntityManager entityManager;
    public WechatFamilyDeletionService(JdbcTemplate jdbc, WechatChatContextRepository contexts,
            WechatTaskConversationRepository conversations, EntityManager entityManager) {
        this.jdbc=jdbc; this.contexts=contexts; this.conversations=conversations;
        this.entityManager=entityManager;
    }

    /** 必须在家庭锁之前调用，维持路由→家庭→会话锁序，避免确认写入外键的锁环。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public List<WechatChatContext> lockRoutes(Long familyId) {
        requireFamily(familyId);
        var owned = new HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT wechat_bind_id FROM wechat_task_conversations WHERE family_id=?", Long.class, familyId));
        var result = new ArrayList<WechatChatContext>();
        for (var candidate : contexts.deletionRouteCandidates(familyId)) {
            if (owned.contains(candidate.getWechatBindId()) || references(candidate, familyId))
                contexts.findByWechatBindIdForUpdate(candidate.getWechatBindId()).ifPresent(locked -> {
                    // 候选枚举后路由可能被修改；持锁刷新，不能用一级缓存覆盖其他家庭的新偏好。
                    entityManager.refresh(locked, LockModeType.PESSIMISTIC_WRITE);
                    result.add(locked);
                });
        }
        return result;
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public void softDelete(Long familyId, List<WechatChatContext> lockedRoutes, Instant now) {
        requireFamily(familyId);
        clearRoutes(familyId, lockedRoutes);
        var ids = jdbc.queryForList("SELECT id FROM wechat_task_conversations WHERE family_id=? ORDER BY id", Long.class, familyId);
        for (Long id : ids) {
            var conversation = conversations.findForRetention(id, familyId).orElseThrow();
            if (conversation.getStatus().isRecoverable()) {
                conversation.setStatus(ConversationStatus.CANCELLED);
                conversation.setPausedFromStatus(null); conversation.setActiveSlot(null);
            }
            if (conversation.getDeletedAt()==null) conversation.setDeletedAt(now);
            conversation.setRollingSummary(null); conversation.setSummaryThroughMessageId(null);
            conversation.setSlotState(Map.of());
            conversations.saveAndFlush(conversation);
        }
        jdbc.update("""
                UPDATE wechat_conversation_messages SET processing_status='SKIPPED', lease_owner=NULL,
                  lease_until=NULL, last_error='FAMILY_DELETED', processed_at=?, updated_at=?
                WHERE family_id=? AND processing_status IN ('PENDING','PROCESSING','WAITING_FAMILY')
                """, Timestamp.from(now), Timestamp.from(now), familyId);
        // 已开始的 HTTP 无法撤回；活租约保留，结束或租约过期后再收敛。
        jdbc.update("""
                UPDATE wechat_message_outbox SET status='CANCELLED', lease_owner=NULL, lease_until=NULL,
                  next_retry_at=NULL, last_error='FAMILY_DELETED', updated_at=?
                WHERE family_id=? AND (status='PENDING' OR (status='SENDING' AND lease_until<=?))
                """, Timestamp.from(now), familyId, Timestamp.from(now));
        jdbc.update("DELETE FROM family_task_experience_evidence WHERE family_id=?", familyId);
        jdbc.update("DELETE FROM family_task_experiences WHERE family_id=?", familyId);
    }

    /** 空家庭仍按原任务删除规则物理删除；先清新增子表，避免非级联确认外键阻塞。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void physicalDelete(Long familyId, List<WechatChatContext> lockedRoutes) {
        requireFamily(familyId);
        clearRoutes(familyId, lockedRoutes);
        for (String table : List.of("wechat_message_outbox", "wechat_confirmation_tasks", "wechat_task_confirmations",
                "wechat_candidate_versions", "wechat_conversation_messages", "wechat_task_conversations",
                "family_task_experience_evidence", "family_task_experiences"))
            jdbc.update("DELETE FROM " + table + " WHERE family_id=?", familyId);
    }

    /** 补偿删除时并发到达的新会话及崩溃遗留发送租约，不依赖原文保留清理开关。 */
    @Transactional
    public void repairDeletedFamily(Long familyId, Instant now) {
        requireFamily(familyId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM families WHERE id=? AND deleted_at IS NOT NULL", Integer.class, familyId)==0)
            return;
        var routes=lockRoutes(familyId);
        var found=jdbc.queryForList("SELECT id FROM families WHERE id=? AND deleted_at IS NOT NULL FOR UPDATE", Long.class, familyId);
        if (!found.isEmpty()) softDelete(familyId, routes, now);
    }

    /** 只枚举已软删除家庭 ID，修复时再逐家庭锁定和校验。 */
    @Transactional(readOnly=true)
    public List<Long> deletedFamilies(int page) {
        return jdbc.queryForList("SELECT id FROM families WHERE deleted_at IS NOT NULL ORDER BY id LIMIT 20 OFFSET ?", Long.class, page*20);
    }

    private void clearRoutes(Long familyId, List<WechatChatContext> routes) {
        for (var route : routes) {
            boolean changed=false, rerouted=false;
            if (Objects.equals(route.getCurrentFamilyId(), familyId)) {
                route.setCurrentFamilyId(null); changed=true; rerouted=true;
                route.setLeaseOwner(null); route.setLeaseUntil(null);
            }
            if (Objects.equals(route.getDefaultFamilyId(), familyId)) {
                route.setDefaultFamilyId(null); changed=true;
            }
            if (references(route.getPendingFamilySelection(), familyId)) {
                route.setPendingFamilySelection(null); changed=true; rerouted=true;
            }
            if (route.getPendingHistoryDelete()!=null) {
                var retained=new LinkedHashMap<>(route.getPendingHistoryDelete());
                if (references(retained.get("history_view"),familyId)) { retained.remove("history_view"); changed=true; }
                if (references(retained.get("challenge"),familyId)) { retained.remove("challenge"); changed=true; }
                if (retained.containsKey("family_id") && numberEquals(retained.get("family_id"),familyId)) {
                    retained.clear(); changed=true;
                }
                if (changed) route.setPendingHistoryDelete(retained.isEmpty() ? null : retained);
            }
            // 默认偏好被清除但当前家庭未变时，不使其他家庭的有效历史编号/AI 轮次失效。
            if (rerouted) route.setRouteRevision((route.getRouteRevision()==null ? 0L : route.getRouteRevision())+1);
            if (changed) contexts.save(route);
        }
    }

    private boolean references(WechatChatContext context, Long familyId) {
        return Objects.equals(context.getCurrentFamilyId(),familyId) || Objects.equals(context.getDefaultFamilyId(),familyId)
                || references(context.getPendingFamilySelection(),familyId) || references(context.getPendingHistoryDelete(),familyId);
    }
    private boolean references(Object value, Long familyId) {
        if (!(value instanceof Map<?,?> map)) return false;
        if (numberEquals(map.get("family_id"), familyId)) return true;
        if (map.get("candidate_family_ids") instanceof List<?> ids && ids.stream().anyMatch(id -> numberEquals(id,familyId))) return true;
        return map.values().stream().anyMatch(child -> references(child, familyId));
    }
    private boolean numberEquals(Object value, Long familyId) {
        return value instanceof Number number && number.longValue()==familyId;
    }
    private void requireFamily(Long familyId) {
        if (familyId==null || familyId<1) throw new IllegalArgumentException("家庭删除清理必须指定有效家庭");
    }
}
