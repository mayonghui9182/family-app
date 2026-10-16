package com.familyapp.wechat.conversation;

import com.familyapp.family.FamilyRole;
import com.familyapp.task.TaskPriority;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * backend 调用 agent-service 持续任务会话端点的受限客户端。
 *
 * <p>请求类型从结构上不包含 openid、微信绑定详情或密钥字段；内部密钥只放请求头。
 * 调用前验证输入 schema，收到响应后再次验证动作、长度和当前家庭引用。任何失败都
 * 返回不允许推进会话修订的安全结果，调用方不能据此落候选或执行业务写入。</p>
 */
@Component
public class WechatConversationAgentClient {
    @org.springframework.beans.factory.annotation.Autowired
    private WechatConversationOperationalMetrics metrics;

    static final int CONNECT_TIMEOUT_MS = 3_000;
    static final int READ_TIMEOUT_MS = 45_000;
    static final int MAX_RECENT_MESSAGES = 16;
    static final int MAX_EXPERIENCES = 5;
    static final int MIN_CANDIDATE_TASKS = 1;
    static final int MAX_CANDIDATE_TASKS = 20;

    private static final String TURN_PATH = "/api/internal/task-conversations/turn";
    private static final String FALLBACK_REPLY = "我暂时没整理好，请稍后再发一次。";

    private final String baseUrl;
    private final String internalSecret;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public WechatConversationAgentClient(
            @Value("$" + "{agent-service.base-url:http://localhost:8700}") String baseUrl,
            @Value("$" + "{internal-api.secret}") String internalSecret) {
        this(baseUrl, internalSecret, buildTemplate(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS));
    }

    /** 测试构造器：允许挂接 MockRestServiceServer。 */
    WechatConversationAgentClient(
            String baseUrl,
            String internalSecret,
            RestTemplate restTemplate) {
        this.baseUrl = requireText(baseUrl, "agent-service 地址", 500);
        this.internalSecret = requireText(internalSecret, "内部密钥", 500);
        this.restTemplate = restTemplate;
        this.objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .serializationInclusion(JsonInclude.Include.ALWAYS)
                .build();
    }

    static RestTemplate buildTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    /**
     * 执行单轮推理。该客户端不接收实体或仓储，因此失败路径自身无法推进 revision。
     */
    public TurnCallResult turn(TurnRequest request) {
        try {
            validateRequest(request);
        } catch (RuntimeException exception) {
            return TurnCallResult.fallback(FailureKind.INVALID_REQUEST);
        }

        try {
            String requestJson = objectMapper.writeValueAsString(request);
            ResponseEntity<String> response = restTemplate.postForEntity(
                    baseUrl + TURN_PATH,
                    new HttpEntity<>(requestJson, jsonHeaders()),
                    String.class);
            TurnActionWire wire = objectMapper.readValue(response.getBody(), TurnActionWire.class);
            TurnAction action = validateResponse(wire, request);
            return TurnCallResult.accepted(action);
        } catch (ResourceAccessException exception) {
            return TurnCallResult.fallback(FailureKind.TIMEOUT);
        } catch (RestClientResponseException exception) {
            return TurnCallResult.fallback(remoteFailureKind(exception));
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            if (metrics != null) metrics.event(WechatConversationOperationalMetrics.Event.AI_REJECTED);
            return TurnCallResult.fallback(FailureKind.INVALID_RESPONSE);
        } catch (RestClientException exception) {
            return TurnCallResult.fallback(FailureKind.REMOTE_FAILURE);
        }
    }

    /** 保留稳定业务错误分类，格式错误不应重复消耗模型调用。 */
    private FailureKind remoteFailureKind(RestClientResponseException exception) {
        try {
            var body = objectMapper.readTree(exception.getResponseBodyAsString());
            int code = body == null ? 0 : body.path("code").asInt();
            if (code == 56001) return FailureKind.TIMEOUT;
            if (code == 56002) return FailureKind.INVALID_RESPONSE;
        } catch (JsonProcessingException ignored) {
            // 非结构化上游错误仍按可重试的远端故障处理。
        }
        return FailureKind.REMOTE_FAILURE;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Secret", internalSecret);
        return headers;
    }

    private TurnAction validateResponse(TurnActionWire wire, TurnRequest request) {
        if (wire == null || wire.action() == null || wire.slotPatch() == null) {
            throw new IllegalArgumentException("Agent 动作字段不完整");
        }
        int replyLimit = wire.action() == ActionType.PRESENT_CANDIDATE ? 1_200 : 800;
        requireText(wire.reply(), "reply", replyLimit);
        if (wire.slotPatch().size() > 20) {
            throw new IllegalArgumentException("slot_patch 超出上限");
        }

        Set<Long> experienceIds = experienceIds(request.experiences());
        for (Map.Entry<String, SlotValue> entry : wire.slotPatch().entrySet()) {
            if (!request.allowedSlotKeys().contains(entry.getKey())) {
                throw new IllegalArgumentException("Agent 返回未允许槽位");
            }
            validateSlotValue(entry.getValue(), experienceIds, request.messageId());
        }

        List<CandidateTask> tasks;
        if (wire.action() == ActionType.PRESENT_CANDIDATE) {
            requireSizeBetween(wire.tasks(), MIN_CANDIDATE_TASKS, MAX_CANDIDATE_TASKS, "tasks");
            validateTasks(wire.tasks(), request);
            tasks = List.copyOf(wire.tasks());
        } else {
            if (wire.tasks() != null) {
                throw new IllegalArgumentException("非候选动作不得携带 tasks");
            }
            tasks = List.of();
        }
        return new TurnAction(wire.action(), wire.reply(), tasks, Map.copyOf(wire.slotPatch()));
    }

    private void validateRequest(TurnRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        requirePositive(request.conversationId(), "conversation_id");
        requireNonNegative(request.routeRevision(), "route_revision");
        requireNonNegative(request.currentRevision(), "current_revision");
        requirePositive(request.messageId(), "message_id");
        requireText(request.userText(), "user_text", 2_000);
        validateFamily(request.family());
        validateExperiences(request.experiences());
        validateCategories(request.categories());
        validateMembers(request.members());

        requireMaxSize(request.allowedSlotKeys(), 50, "allowed_slot_keys");
        Set<String> allowedKeys = new HashSet<>();
        for (String key : request.allowedSlotKeys()) {
            if (!allowedKeys.add(requireText(key, "slot key", 100))) {
                throw new IllegalArgumentException("allowed_slot_keys 不得重复");
            }
        }

        requireMaxSize(request.slotState(), 50, "slot_state");
        Set<Long> experienceIds = experienceIds(request.experiences());
        for (Map.Entry<String, SlotValue> entry : request.slotState().entrySet()) {
            if (!allowedKeys.contains(entry.getKey())) {
                throw new IllegalArgumentException("slot_state 含未允许槽位");
            }
            validateSlotValue(entry.getValue(), experienceIds, null);
        }

        if (request.currentCandidate() != null) {
            requirePositive(request.currentCandidate().revision(), "candidate revision");
            requireSizeBetween(request.currentCandidate().tasks(),
                    MIN_CANDIDATE_TASKS, MAX_CANDIDATE_TASKS, "candidate tasks");
            validateTasks(request.currentCandidate().tasks(), request);
        }
        if (request.rollingSummary() != null) {
            requireText(request.rollingSummary(), "rolling_summary", 4_000);
        }
        validateRecentMessages(request.recentMessages());
    }

    private void validateFamily(FamilyContext family) {
        if (family == null || family.actorRole() == null) {
            throw new IllegalArgumentException("当前家庭信息不完整");
        }
        requirePositive(family.familyId(), "family_id");
        requireText(family.familyName(), "family_name", 50);
        requirePositive(family.actorUserId(), "actor_user_id");
    }

    private void validateTasks(List<CandidateTask> tasks, TurnRequest request) {
        Set<Long> categoryIds = idsFromCategories(request.categories());
        Set<Long> profileIds = idsFromMembers(request.members());
        for (CandidateTask task : tasks) {
            if (task == null || task.priority() == null) {
                throw new IllegalArgumentException("候选任务字段不完整");
            }
            requireText(task.title(), "task title", 200);
            if (task.description() == null || task.description().length() > 2_000) {
                throw new IllegalArgumentException("task description 不符合约束");
            }
            if (task.categoryId() != null && !categoryIds.contains(task.categoryId())) {
                throw new IllegalArgumentException("候选引用了其他家庭分类");
            }
            if (task.assigneeProfileId() != null
                    && !profileIds.contains(task.assigneeProfileId())) {
                throw new IllegalArgumentException("候选引用了其他家庭成员");
            }
        }
    }

    private void validateRecentMessages(List<RecentMessage> messages) {
        requireMaxSize(messages, MAX_RECENT_MESSAGES, "recent_messages");
        Set<Long> ids = new HashSet<>();
        for (RecentMessage message : messages) {
            if (message == null || message.direction() == null || message.createdAt() == null) {
                throw new IllegalArgumentException("最近消息字段不完整");
            }
            requirePositive(message.messageId(), "recent message id");
            requireText(message.content(), "recent message content", 2_000);
            if (!ids.add(message.messageId())) {
                throw new IllegalArgumentException("最近消息 ID 不得重复");
            }
        }
    }

    private void validateExperiences(List<ExperienceContext> experiences) {
        requireMaxSize(experiences, MAX_EXPERIENCES, "experiences");
        Set<Long> ids = new HashSet<>();
        for (ExperienceContext experience : experiences) {
            if (experience == null) {
                throw new IllegalArgumentException("经验不能为空");
            }
            requirePositive(experience.experienceId(), "experience_id");
            requirePositive(experience.version(), "experience version");
            requireText(experience.normalizedKey(), "normalized_key", 200);
            requireText(experience.valueSummary(), "value_summary", 500);
            if (!ids.add(experience.experienceId())) {
                throw new IllegalArgumentException("经验 ID 不得重复");
            }
        }
    }

    private void validateCategories(List<CategoryReference> categories) {
        requireMaxSize(categories, 200, "categories");
        Set<Long> ids = new HashSet<>();
        for (CategoryReference category : categories) {
            if (category == null) {
                throw new IllegalArgumentException("分类不能为空");
            }
            requirePositive(category.categoryId(), "category_id");
            requireText(category.name(), "category name", 50);
            if (!ids.add(category.categoryId())) {
                throw new IllegalArgumentException("分类 ID 不得重复");
            }
        }
    }

    private void validateMembers(List<MemberReference> members) {
        requireMaxSize(members, 200, "members");
        Set<Long> ids = new HashSet<>();
        for (MemberReference member : members) {
            if (member == null) {
                throw new IllegalArgumentException("成员不能为空");
            }
            requirePositive(member.profileId(), "profile_id");
            requireText(member.displayName(), "display_name", 50);
            if (member.userId() != null) {
                requirePositive(member.userId(), "member user_id");
            }
            if (!ids.add(member.profileId())) {
                throw new IllegalArgumentException("成员档案 ID 不得重复");
            }
        }
    }

    private void validateSlotValue(
            SlotValue slot,
            Set<Long> allowedExperienceIds,
            Long requiredMessageId) {
        if (slot == null || slot.source() == null) {
            throw new IllegalArgumentException("槽位来源不完整");
        }
        validateSlotData(slot.value());
        if (slot.source() == SlotSource.EXPERIENCE_DEFAULT) {
            if (slot.sourceMessageId() != null || slot.experienceId() == null
                    || !allowedExperienceIds.contains(slot.experienceId())) {
                throw new IllegalArgumentException("经验槽位来源无效");
            }
            return;
        }
        if (slot.experienceId() != null || slot.sourceMessageId() == null
                || slot.sourceMessageId() < 1
                || (requiredMessageId != null
                && !slot.sourceMessageId().equals(requiredMessageId))) {
            throw new IllegalArgumentException("消息槽位来源无效");
        }
    }

    private void validateSlotData(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return;
        }
        if (value instanceof List<?> list && list.stream().allMatch(item ->
                item instanceof String || item instanceof Integer || item instanceof Long)) {
            return;
        }
        throw new IllegalArgumentException("槽位值类型不受支持");
    }

    private static Set<Long> experienceIds(List<ExperienceContext> experiences) {
        Set<Long> ids = new HashSet<>();
        for (ExperienceContext experience : experiences) {
            ids.add(experience.experienceId());
        }
        return ids;
    }

    private static Set<Long> idsFromCategories(List<CategoryReference> categories) {
        Set<Long> ids = new HashSet<>();
        for (CategoryReference category : categories) {
            ids.add(category.categoryId());
        }
        return ids;
    }

    private static Set<Long> idsFromMembers(List<MemberReference> members) {
        Set<Long> ids = new HashSet<>();
        for (MemberReference member : members) {
            ids.add(member.profileId());
        }
        return ids;
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " 不符合约束");
        }
        return value;
    }

    private static void requirePositive(Number value, String field) {
        if (value == null || value.longValue() < 1) {
            throw new IllegalArgumentException(field + " 必须是正整数");
        }
    }

    private static void requireNonNegative(Number value, String field) {
        if (value == null || value.longValue() < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
    }

    private static void requireMaxSize(Object value, int maxSize, String field) {
        if (value instanceof List<?> list && list.size() <= maxSize) {
            return;
        }
        if (value instanceof Map<?, ?> map && map.size() <= maxSize) {
            return;
        }
        throw new IllegalArgumentException(field + " 数量不符合约束");
    }

    private static void requireSizeBetween(
            List<?> value,
            int minSize,
            int maxSize,
            String field) {
        if (value == null || value.size() < minSize || value.size() > maxSize) {
            throw new IllegalArgumentException(field + " 数量不符合约束");
        }
    }

    public enum ActionType {
        ASK_CLARIFICATION,
        PRESENT_CANDIDATE,
        REFUSE_OR_HELP
    }

    public enum SlotSource {
        USER_EXPLICIT,
        MODEL_INFERRED,
        EXPERIENCE_DEFAULT
    }

    public enum FailureKind {
        NONE,
        INVALID_REQUEST,
        TIMEOUT,
        REMOTE_FAILURE,
        INVALID_RESPONSE
    }

    public record FamilyContext(
            @JsonProperty("family_id") Long familyId,
            @JsonProperty("family_name") String familyName,
            @JsonProperty("actor_user_id") Long actorUserId,
            @JsonProperty("actor_role") FamilyRole actorRole) {
    }

    public record CategoryReference(
            @JsonProperty("category_id") Long categoryId,
            @JsonProperty("name") String name) {
    }

    public record MemberReference(
            @JsonProperty("profile_id") Long profileId,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("user_id") Long userId) {
    }

    public record RecentMessage(
            @JsonProperty("message_id") Long messageId,
            @JsonProperty("direction") MessageDirection direction,
            @JsonProperty("content") String content,
            @JsonProperty("created_at") Instant createdAt) {
    }

    public record ExperienceContext(
            @JsonProperty("experience_id") Long experienceId,
            @JsonProperty("version") Integer version,
            @JsonProperty("normalized_key") String normalizedKey,
            @JsonProperty("value_summary") String valueSummary) {
    }

    public record SlotValue(
            @JsonProperty("value") Object value,
            @JsonProperty("source") SlotSource source,
            @JsonProperty("source_message_id") Long sourceMessageId,
            @JsonProperty("experience_id") Long experienceId) {
    }

    public record CandidateTask(
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("due_date") LocalDate dueDate,
            @JsonProperty("priority") TaskPriority priority,
            @JsonProperty("category_id") Long categoryId,
            @JsonProperty("assignee_profile_id") Long assigneeProfileId,
            @JsonProperty("category_name")
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            String categoryName) {
        public CandidateTask {
            categoryName = WechatCategoryNames.normalize(categoryName);
            if (categoryId != null && categoryName != null) {
                throw new IllegalArgumentException("已有分类ID与新分类建议不能同时指定");
            }
        }

        /** 兼容历史仅分类ID的候选。 */
        public CandidateTask(String title, String description, LocalDate dueDate, TaskPriority priority, Long categoryId, Long assigneeProfileId) {
            this(title, description, dueDate, priority, categoryId, assigneeProfileId, null);
        }
    }

    public record CandidateSnapshot(
            @JsonProperty("revision") Integer revision,
            @JsonProperty("tasks") List<CandidateTask> tasks) {
    }

    public record TurnRequest(
            @JsonProperty("conversation_id") Long conversationId,
            @JsonProperty("route_revision") Long routeRevision,
            @JsonProperty("current_revision") Integer currentRevision,
            @JsonProperty("message_id") Long messageId,
            @JsonProperty("user_text") String userText,
            @JsonProperty("family") FamilyContext family,
            @JsonProperty("allowed_slot_keys") List<String> allowedSlotKeys,
            @JsonProperty("slot_state") Map<String, SlotValue> slotState,
            @JsonProperty("current_candidate") CandidateSnapshot currentCandidate,
            @JsonProperty("rolling_summary") String rollingSummary,
            @JsonProperty("recent_messages") List<RecentMessage> recentMessages,
            @JsonProperty("experiences") List<ExperienceContext> experiences,
            @JsonProperty("categories") List<CategoryReference> categories,
            @JsonProperty("members") List<MemberReference> members,
            @JsonProperty("received_at") Instant receivedAt) {
        /** 兼容未携带接收时间的既有内部调用。 */
        public TurnRequest(
                Long conversationId,
                Long routeRevision,
                Integer currentRevision,
                Long messageId,
                String userText,
                FamilyContext family,
                List<String> allowedSlotKeys,
                Map<String, SlotValue> slotState,
                CandidateSnapshot currentCandidate,
                String rollingSummary,
                List<RecentMessage> recentMessages,
                List<ExperienceContext> experiences,
                List<CategoryReference> categories,
                List<MemberReference> members) {
            this(conversationId, routeRevision, currentRevision, messageId, userText, family, allowedSlotKeys, slotState, currentCandidate, rollingSummary, recentMessages, experiences, categories, members, null);
        }
    }

    private record TurnActionWire(
            @JsonProperty("action") ActionType action,
            @JsonProperty("reply") String reply,
            @JsonProperty("tasks") List<CandidateTask> tasks,
            @JsonProperty("slot_patch") Map<String, SlotValue> slotPatch) {
    }

    /** 通过响应校验后的统一动作；非 PRESENT 动作的 tasks 固定为空。 */
    public record TurnAction(
            ActionType action,
            String reply,
            List<CandidateTask> tasks,
            Map<String, SlotValue> slotPatch) {
    }

    /**
     * accepted=false 时 action 必为空且 advanceRevision 固定为 false，避免失败路径误落候选。
     */
    public record TurnCallResult(
            boolean accepted,
            boolean advanceRevision,
            TurnAction action,
            String reply,
            FailureKind failureKind) {

        static TurnCallResult accepted(TurnAction action) {
            return new TurnCallResult(
                    true,
                    action.action() == ActionType.PRESENT_CANDIDATE,
                    action,
                    action.reply(),
                    FailureKind.NONE);
        }

        static TurnCallResult fallback(FailureKind failureKind) {
            return new TurnCallResult(false, false, null, FALLBACK_REPLY, failureKind);
        }
    }
}
