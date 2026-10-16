package com.familyapp.wechat.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** 专用保留维护边界：只清正文或过期候选，保留幂等标识、确认哈希和任务关联。 */
@Service
public class WechatConversationRetentionService {
    private final JdbcTemplate jdbc;
    private final WechatTaskConversationRepository conversations;
    private final WechatConversationProperties properties;

    public WechatConversationRetentionService(JdbcTemplate jdbc,
            WechatTaskConversationRepository conversations, WechatConversationProperties properties) {
        this.jdbc = jdbc; this.conversations = conversations; this.properties = properties;
    }

    /** 只枚举维护作用域 ID，不读取跨家庭业务内容，也包含已软删除家庭。 */
    @Transactional(readOnly = true)
    public List<Long> familyScopes(int page) {
        return jdbc.queryForList("""
                SELECT family_id FROM (
                  SELECT family_id FROM wechat_task_conversations
                  UNION SELECT family_id FROM wechat_conversation_messages WHERE family_id IS NOT NULL
                ) scopes ORDER BY family_id LIMIT 20 OFFSET ?
                """, Long.class, page * 20);
    }

    /** 未路由传输审计尚无家庭，维护仅枚举公众号作用域，绝不借此读取家庭数据。 */
    @Transactional(readOnly = true)
    public List<String> unroutedScopes(int page) {
        return jdbc.queryForList("""
                SELECT DISTINCT wechat_account_id FROM wechat_conversation_messages
                WHERE family_id IS NULL AND (content <> '' OR openid <> 'retained')
                ORDER BY wechat_account_id LIMIT 20 OFFSET ?
                """, String.class, page * 20);
    }

    @Transactional
    public Counts cleanFamily(Long familyId, Instant now) {
        if (familyId == null || familyId < 1) throw new IllegalArgumentException("清理必须指定有效家庭");
        var rawCutoff = Timestamp.from(now.minus(properties.getRawMessageRetentionDays(), ChronoUnit.DAYS));
        var summaryCutoff = Timestamp.from(now.minus(properties.getSummaryRetentionDays(), ChronoUnit.DAYS));
        int limit = properties.getRetentionBatchSize();
        int raw = 0, summaries = 0, candidates = 0;
        var conversationIds = jdbc.queryForList("""
                SELECT id FROM wechat_task_conversations
                WHERE family_id=? AND (deleted_at IS NOT NULL OR last_activity_at < ?)
                  AND (rolling_summary IS NOT NULL OR summary_through_message_id IS NOT NULL
                       OR CAST(slot_state AS VARCHAR) <> '{}'
                       OR status IN ('ACTIVE','AWAITING_CONFIRMATION','PAUSED'))
                ORDER BY id LIMIT ?
                """, Long.class, familyId, summaryCutoff, limit);
        for (Long id : conversationIds) {
            var conversation = conversations.findForRetention(id, familyId).orElseThrow();
            boolean hidden = conversation.getDeletedAt() != null;
            if (!hidden && !conversation.getLastActivityAt().isBefore(summaryCutoff.toInstant())) continue;
            // 活跃或等待中的保留例外，防止清空仍在使用的确认快照。
            if (!hidden && conversation.getStatus().isRecoverable() && conversation.getExpiresAt().isAfter(now)) continue;
            if (conversation.getStatus().isRecoverable()) {
                conversation.setStatus(hidden ? ConversationStatus.CANCELLED : ConversationStatus.EXPIRED);
                conversation.setPausedFromStatus(null); conversation.setActiveSlot(null);
            }
            conversation.setRollingSummary(null); conversation.setSummaryThroughMessageId(null);
            conversation.setSlotState(Map.of());
            conversations.saveAndFlush(conversation);
            summaries++;
        }
        var candidateIds = jdbc.queryForList("""
                SELECT v.id FROM wechat_candidate_versions v JOIN wechat_task_conversations c
                  ON c.id=v.conversation_id AND c.family_id=v.family_id
                WHERE v.family_id=? AND (c.deleted_at IS NOT NULL OR
                  (v.created_at < ? AND (v.revision <> c.current_revision
                    OR c.status IN ('CONFIRMED','CANCELLED','EXPIRED') OR c.expires_at <= ?)))
                ORDER BY v.id LIMIT ?
                """, Long.class, familyId, summaryCutoff, Timestamp.from(now), limit);
        for (Long id : candidateIds) {
            Long conversationId = jdbc.queryForObject(
                    "SELECT conversation_id FROM wechat_candidate_versions WHERE id=? AND family_id=?",
                    Long.class, id, familyId);
            var conversation = conversations.findForRetention(conversationId, familyId).orElseThrow();
            if (conversation.getDeletedAt() == null && conversation.getStatus().isRecoverable()
                    && conversation.getCurrentRevision() != null
                    && jdbc.queryForObject("SELECT revision FROM wechat_candidate_versions WHERE id=? AND family_id=?",
                        Integer.class, id, familyId).equals(conversation.getCurrentRevision())) {
                if (conversation.getExpiresAt().isAfter(now)) continue;
                conversation.setStatus(ConversationStatus.EXPIRED);
                conversation.setPausedFromStatus(null); conversation.setActiveSlot(null);
                conversations.saveAndFlush(conversation);
            }
            // 先解绑可空候选外键，最小确认哈希、修订和唯一幂等键始终保留。
            jdbc.update("UPDATE wechat_task_confirmations SET candidate_version_id=NULL WHERE family_id=? AND candidate_version_id=?",
                    familyId, id);
            candidates += jdbc.update("DELETE FROM wechat_candidate_versions WHERE family_id=? AND id=?", familyId, id);
            jdbc.update("UPDATE wechat_task_conversations SET version=version+1 WHERE family_id=? AND id=?",
                    familyId, conversationId);
        }
        var messageIds = jdbc.queryForList("""
                SELECT m.id FROM wechat_conversation_messages m WHERE m.family_id=?
                  AND (m.content <> '' OR m.openid <> 'retained')
                  AND (m.received_at < ? OR EXISTS (SELECT c.id FROM wechat_task_conversations c
                    WHERE c.id=m.conversation_id AND c.family_id=m.family_id AND c.deleted_at IS NOT NULL))
                  AND m.processing_status IN ('PROCESSED','FAILED','SKIPPED')
                  AND NOT EXISTS (SELECT o.id FROM wechat_message_outbox o WHERE o.family_id=m.family_id
                    AND o.message_id=m.id AND o.status IN ('PENDING','SENDING'))
                ORDER BY m.id LIMIT ?
                """, Long.class, familyId, rawCutoff, limit);
        for (Long id : messageIds) {
            Long conversationId = jdbc.queryForObject(
                    "SELECT conversation_id FROM wechat_conversation_messages WHERE family_id=? AND id=?",
                    Long.class, familyId, id);
            if (conversationId != null) {
                // 同摘要提交使用会话锁；清正文同时推进版本，拒绝清理前生成的摘要。
                jdbc.queryForList("SELECT id FROM wechat_task_conversations WHERE family_id=? AND id=? FOR UPDATE",
                        Long.class, familyId, conversationId);
            }
            int changed = jdbc.update("""
                    UPDATE wechat_conversation_messages m SET content='', openid='retained', last_error=NULL, updated_at=?
                    WHERE m.family_id=? AND m.id=? AND (m.content <> '' OR m.openid <> 'retained')
                      AND m.processing_status IN ('PROCESSED','FAILED','SKIPPED')
                      AND NOT EXISTS (SELECT o.id FROM wechat_message_outbox o WHERE o.family_id=m.family_id
                        AND o.message_id=m.id AND o.status IN ('PENDING','SENDING'))
                    """, Timestamp.from(now), familyId, id);
            raw += changed;
            if (changed > 0 && conversationId != null)
                jdbc.update("UPDATE wechat_task_conversations SET version=version+1 WHERE family_id=? AND id=?",
                        familyId, conversationId);
        }
        return new Counts(raw, summaries, candidates);
    }

    @Transactional
    public int cleanUnrouted(String accountId, Instant now) {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("未路由清理必须指定公众号");
        var ids = jdbc.queryForList("""
                SELECT id FROM wechat_conversation_messages WHERE family_id IS NULL AND wechat_account_id=?
                  AND (content <> '' OR openid <> 'retained') AND received_at < ?
                  AND processing_status IN ('PROCESSED','FAILED','SKIPPED')
                ORDER BY id LIMIT ?
                """, Long.class, accountId,
                Timestamp.from(now.minus(properties.getRawMessageRetentionDays(), ChronoUnit.DAYS)),
                properties.getRetentionBatchSize());
        int count = 0;
        for (Long id : ids) count += jdbc.update("""
                UPDATE wechat_conversation_messages SET content='', openid='retained', last_error=NULL, updated_at=?
                WHERE family_id IS NULL AND wechat_account_id=? AND id=?
                  AND processing_status IN ('PROCESSED','FAILED','SKIPPED')
                """, Timestamp.from(now), accountId, id);
        return count;
    }

    public record Counts(int messages, int summaries, int candidates) { }
}
