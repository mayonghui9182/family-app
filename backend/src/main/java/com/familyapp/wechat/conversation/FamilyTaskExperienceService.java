package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRepository;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** 家庭偏好版本服务；只相信当前租约持有者读取的真实入站消息。 */
@Service
public class FamilyTaskExperienceService {
    @org.springframework.beans.factory.annotation.Autowired
    private WechatConversationOperationalMetrics metrics;
    private static final Pattern PREFERENCE = Pattern.compile(
            "^以后\\s*(.{1,40}?)\\s*(不要|不再)?默认\\s*(.{1,100})$");
    private final FamilyRepository families;
    private final WechatChatContextRepository contexts;
    private final WechatConversationMessageRepository messages;
    private final WechatFamilyAccessGuard access;
    private final FamilyTaskExperienceRepository experiences;
    private final FamilyTaskExperienceEvidenceRepository evidence;

    public FamilyTaskExperienceService(FamilyRepository families, WechatChatContextRepository contexts,
            WechatConversationMessageRepository messages, WechatFamilyAccessGuard access,
            FamilyTaskExperienceRepository experiences, FamilyTaskExperienceEvidenceRepository evidence) {
        this.families = families;
        this.contexts = contexts;
        this.messages = messages;
        this.access = access;
        this.experiences = experiences;
        this.evidence = evidence;
    }

    public static Preference preference(String text) {
        if (text == null) return null;
        var matcher = PREFERENCE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).strip());
        if (!matcher.matches()) return null;
        String scope = normalize(matcher.group(1));
        String value = matcher.group(3).strip();
        // 拒绝含多个指令的文本，不能将模糊肯定、疑问或本轮修改当成长期授权。
        if (scope.isBlank() || value.isBlank() || (scope + value).matches(".*[。！？!?；;\\n].*")) return null;
        return new Preference(scope, value, matcher.group(2) != null);
    }

    @Transactional
    public String setExplicit(MessageLease lease, long userId, long familyId, Instant now) {
        var context = contexts.findByWechatBindIdForUpdate(lease.wechatBindId())
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (!Objects.equals(context.getUserId(), userId)
                || !Objects.equals(context.getCurrentFamilyId(), familyId)
                || !Objects.equals(context.getLeaseOwner(), lease.workerId())
                || context.getLeaseUntil() == null || !context.getLeaseUntil().isAfter(now))
            throw new WechatFamilyAccessDeniedException();
        // 家庭行锁串行化版本创建，且与家庭删除共用路由→家庭锁序。
        families.findActiveByIdForUpdate(familyId).orElseThrow(WechatFamilyAccessDeniedException::new);
        access.requireAccess(userId, familyId, WechatFamilyAccessGuard.AccessOperation.ROUTE_MESSAGE);
        var source = messages.findByIdAndFamilyId(lease.messageId(), familyId)
                .orElseThrow(WechatFamilyAccessDeniedException::new);
        if (source.getDirection() != MessageDirection.INBOUND
                || !Objects.equals(source.getWechatBindId(), lease.wechatBindId())
                || !Objects.equals(source.getUserId(), userId)
                || source.getProcessingStatus() != MessageProcessingStatus.PROCESSING
                || !Objects.equals(source.getLeaseOwner(), lease.workerId())
                || source.getLeaseUntil() == null || !source.getLeaseUntil().isAfter(now))
            throw new WechatFamilyAccessDeniedException();
        Preference preference = preference(source.getContent());
        if (preference == null) return "请明确发送“以后买菜默认给妈妈”或“以后买菜不要默认给妈妈”。";
        var previousEvidence = evidence.findByFamilyIdAndSourceTypeAndSourceId(familyId,
                ExperienceEvidenceSourceType.USER_MESSAGE, source.getId().toString());
        if (!previousEvidence.isEmpty()) return reply(preference.disabled());
        String key = "preference:" + preference.scope();
        var previous = experiences.findFirstByFamilyIdAndNormalizedKeyOrderByVersionDesc(familyId, key);
        // 旧版本不再被活动经验查询注入，但版本和来源证据保持可追溯。
        experiences.findByFamilyIdAndNormalizedKeyOrderByVersionDesc(familyId, key).forEach(old -> {
            if (old.getStatus() == ExperienceStatus.ACTIVE) {
                old.setStatus(ExperienceStatus.DISABLED);
                experiences.save(old);
            }
        });
        var next = new FamilyTaskExperience();
        next.setFamilyId(familyId);
        next.setNormalizedKey(key);
        next.setVersion(previous.map(item -> item.getVersion() + 1).orElse(1));
        next.setStatus(preference.disabled() ? ExperienceStatus.DISABLED : ExperienceStatus.ACTIVE);
        next.setActivationSource(preference.disabled() ? ExperienceActivationSource.USER_REJECTED
                : ExperienceActivationSource.USER_EXPLICIT);
        next.setValuePayload(Map.of("default_suggestion", preference.value()));
        next.setScopePayload(Map.of("keyword", preference.scope()));
        next.setConfidence(BigDecimal.ONE);
        next.setEvidenceCount(1);
        experiences.save(next);
        var proof = new FamilyTaskExperienceEvidence();
        proof.setFamilyId(familyId);
        proof.setExperienceId(next.getId());
        proof.setSourceType(ExperienceEvidenceSourceType.USER_MESSAGE);
        proof.setSourceId(source.getId().toString());
        proof.setIndependenceKey("message:" + source.getId());
        proof.setContentHash(hash(source.getContent()));
        proof.setObservedAt(now);
        evidence.save(proof);
        if (!preference.disabled() && metrics != null)
            metrics.event(WechatConversationOperationalMetrics.Event.EXPERIENCE_ACTIVATED);
        return reply(preference.disabled());
    }

    private String reply(boolean disabled) {
        return disabled ? "已停用当前家庭的这条默认偏好。已有任务不变。"
                : "已记住当前家庭的默认偏好。本轮明确要求优先，创建任务仍需确认。";
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", "").toLowerCase(Locale.ROOT);
    }

    static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record Preference(String scope, String value, boolean disabled) { }
}
