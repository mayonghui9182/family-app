package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRepository;
import com.familyapp.task.Task;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 确认之后的独立证据投影事务；不读取未确认候选，也不写实际任务。 */
@Service
public class FamilyTaskExperienceProjection {
    @org.springframework.beans.factory.annotation.Autowired
    private WechatConversationOperationalMetrics metrics;
    private final FamilyRepository families;
    private final WechatTaskConfirmationRepository confirmations;
    private final WechatConfirmationTaskRepository links;
    private final TaskRepository tasks;
    private final FamilyTaskExperienceRepository experiences;
    private final FamilyTaskExperienceEvidenceRepository evidence;
    private final ObjectMapper mapper;

    public FamilyTaskExperienceProjection(FamilyRepository families,
            WechatTaskConfirmationRepository confirmations, WechatConfirmationTaskRepository links,
            TaskRepository tasks, FamilyTaskExperienceRepository experiences,
            FamilyTaskExperienceEvidenceRepository evidence, ObjectMapper mapper) {
        this.families = families;
        this.confirmations = confirmations;
        this.links = links;
        this.tasks = tasks;
        this.experiences = experiences;
        this.evidence = evidence;
        this.mapper = mapper.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void projectConfirmation(long familyId, long confirmationId, Instant now) {
        if (families.findActiveByIdForUpdate(familyId).isEmpty()) return;
        if (confirmations.findByIdAndFamilyId(confirmationId, familyId).isEmpty()) return;
        for (var link : links.findByConfirmationIdAndFamilyIdOrderByCandidateItemIndexAsc(confirmationId, familyId))
            project(link, now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observe(long familyId, long linkId, Instant now) {
        if (families.findActiveByIdForUpdate(familyId).isEmpty()) return;
        var link = links.findByIdAndFamilyId(linkId, familyId);
        if (link.isEmpty() || confirmations.findByIdAndFamilyId(link.get().getConfirmationId(), familyId).isEmpty()) return;
        project(link.get(), now);
    }

    private void project(WechatConfirmationTask link, Instant now) {
        var task = tasks.findByIdAndFamilyId(link.getTaskId(), link.getFamilyId());
        if (task.isEmpty()) return;
        Map<String, Object> initial = link.getInitialTaskSnapshot();
        if (!(initial.get("family_id") instanceof Number family)
                || family.longValue() != link.getFamilyId()
                || !(initial.get("task_id") instanceof Number taskId)
                || taskId.longValue() != link.getTaskId()) return;
        String scope = FamilyTaskExperienceService.normalize(String.valueOf(initial.get("title")));
        if (scope.isBlank()) return;
        // 初始证据只来自实际提交快照；当前结果与初始值分别保留来源。
        for (String field : List.of("assignee_id", "assignee_profile_id", "category_id", "priority", "due_date")) {
            Object value = initial.get(field);
            if (value != null) support(link, scope, field, value, ExperienceEvidenceSourceType.CONFIRMED_TASK, now);
        }
        Map<String, Object> current = current(task.get());
        String fingerprint = hash(current);
        if (Objects.equals(fingerprint, link.getLastObservedFingerprint())) return;
        for (String field : List.of("assignee_id", "assignee_profile_id", "category_id", "priority", "due_date", "title", "description", "status")) {
            Object value = current.get(field);
            if (value != null && !Objects.equals(String.valueOf(value), String.valueOf(initial.get(field))))
                support(link, scope, field, value, ExperienceEvidenceSourceType.TASK_OBSERVATION, now);
        }
        link.setLastObservedFingerprint(fingerprint);
        links.save(link);
    }

    private void support(WechatConfirmationTask link, String scope, String field, Object value,
                         ExperienceEvidenceSourceType sourceType, Instant now) {
        long familyId = link.getFamilyId();
        // 用户明确偏好或否定优先；自动证据不能悄悄覆盖明确决定。
        var explicit = experiences.findFirstByFamilyIdAndNormalizedKeyOrderByVersionDesc(familyId, "preference:" + scope);
        if (explicit.isPresent()) return;
        String key = "learned:" + FamilyTaskExperienceService.hash(scope) + ":" + field + ":" + hash(value);
        var previous = experiences.findFirstByFamilyIdAndNormalizedKeyOrderByVersionDesc(familyId, key);
        String independence = "task:" + link.getTaskId();
        if (previous.isPresent() && evidence.findByFamilyIdAndExperienceIdAndIndependenceKey(
                familyId, previous.get().getId(), independence).isPresent()) return;
        var proofs = previous.map(old -> evidence.findByFamilyIdAndExperienceIdOrderByObservedAtAsc(familyId, old.getId()))
                .orElse(List.of());
        int count = proofs.size() + 1;
        var next = new FamilyTaskExperience();
        next.setFamilyId(familyId);
        next.setNormalizedKey(key);
        next.setVersion(previous.map(old -> old.getVersion() + 1).orElse(1));
        next.setActivationSource(ExperienceActivationSource.TASK_EVIDENCE);
        next.setStatus(count >= 3 ? ExperienceStatus.ACTIVE : ExperienceStatus.CANDIDATE);
        next.setEvidenceCount(count);
        next.setConfidence(BigDecimal.valueOf(Math.min(0.95, count / 4.0)));
        // 结果状态仅供参考，不是建议把新任务直接建成完成或取消状态。
        next.setValuePayload(Map.of(field.equals("status") ? "observed_outcome" : field, value));
        next.setScopePayload(Map.of("keyword", scope));
        previous.ifPresent(old -> {
            if (old.getStatus() == ExperienceStatus.ACTIVE) {
                old.setStatus(ExperienceStatus.DISABLED);
                experiences.save(old);
            }
        });
        experiences.save(next);
        if (next.getStatus() == ExperienceStatus.ACTIVE
                && previous.map(old -> old.getEvidenceCount() < 3).orElse(true) && metrics != null)
            metrics.event(WechatConversationOperationalMetrics.Event.EXPERIENCE_ACTIVATED);
        for (var proof : proofs) addProof(next, proof.getSourceType(), proof.getSourceId(),
                proof.getIndependenceKey(), proof.getContentHash(), proof.getObservedAt());
        addProof(next, sourceType, link.getTaskId().toString(), independence, hash(Map.of(field, value)), now);
    }

    private void addProof(FamilyTaskExperience experience, ExperienceEvidenceSourceType type, String source,
                          String independence, String hash, Instant observed) {
        var proof = new FamilyTaskExperienceEvidence();
        proof.setFamilyId(experience.getFamilyId());
        proof.setExperienceId(experience.getId());
        proof.setSourceType(type);
        proof.setSourceId(source);
        proof.setIndependenceKey(independence);
        proof.setContentHash(hash);
        proof.setObservedAt(observed);
        evidence.save(proof);
    }

    private Map<String, Object> current(Task task) {
        var current = new LinkedHashMap<String, Object>();
        current.put("title", task.getTitle());
        current.put("description", task.getDescription());
        current.put("assignee_id", task.getAssigneeId());
        current.put("assignee_profile_id", task.getAssigneeProfileId());
        current.put("category_id", task.getCategoryId());
        current.put("due_date", task.getDueDate() == null ? null : task.getDueDate().toString());
        current.put("priority", task.getPriority().name());
        current.put("status", task.getStatus().name());
        return current;
    }

    private String hash(Object value) {
        try {
            return FamilyTaskExperienceService.hash(mapper.writeValueAsString(value));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("经验来源无法规范化", exception);
        }
    }
}
