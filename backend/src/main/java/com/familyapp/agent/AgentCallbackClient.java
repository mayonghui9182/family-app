package com.familyapp.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * backend → agent-service 内网回调客户端（规格组 B）：
 * B-1 拆解读超时 30 秒；B-2 单家庭总结单分类读超时 60 秒。
 * 自动携带 X-Internal-Secret 头。传输/解析失败抛出，由调用方按降级语义处理
 * （B-1 fallback：直建普通 PENDING 任务 + 中文提示便签）。
 *
 * Change 14 追加 A-2 草稿模式（{@link #decomposeDraft}）：走用户态鉴权
 * （X-Family-Id + X-Access-Token，绝不带内网密钥），读超时 60 秒独立模板。
 */
@Component
public class AgentCallbackClient {

    /** B-1 响应：decomposed=false 表示 agent-service 未完成拆解，调用方走降级。 */
    public record DecomposeResult(
            @JsonProperty("decomposed") boolean decomposed,
            @JsonProperty("task_id") Long taskId,
            @JsonProperty("message") String message) {
    }

    /**
     * A-2 草稿模式返回的子任务。agent-service 在 session 模式下 sink 只填
     * title/description/sort_order 三字段（七字段是 A-1 SSE proposal 帧的形态），
     * 故此处只声明三字段，多出的字段忽略。
     */
    public record DraftSubtask(
            @JsonProperty("title") String title,
            @JsonProperty("description") String description,
            @JsonProperty("sort_order") Integer sortOrder) {
    }

    /** A-2 草稿拆解连接超时 5 秒。 */
    static final int DRAFT_CONNECT_TIMEOUT_MS = 5_000;
    /** A-2 草稿拆解读超时 60 秒（LLM 拆解 + proposal 落 session 的整体兜底）。 */
    static final int DRAFT_READ_TIMEOUT_MS = 60_000;

    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final RestTemplate decomposeTemplate;
    private final RestTemplate summaryTemplate;
    private final RestTemplate draftTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String internalSecret;

    @Autowired
    public AgentCallbackClient(
            @Value("${agent-service.base-url:http://localhost:8700}") String baseUrl,
            @Value("${internal-api.secret}") String internalSecret) {
        this.baseUrl = baseUrl;
        this.internalSecret = internalSecret;
        this.decomposeTemplate = buildTemplate(CONNECT_TIMEOUT_MS, 30_000);
        this.summaryTemplate = buildTemplate(CONNECT_TIMEOUT_MS, 60_000);
        this.draftTemplate = buildTemplate(DRAFT_CONNECT_TIMEOUT_MS, DRAFT_READ_TIMEOUT_MS);
    }

    /** 测试构造器：注入挂好 MockRestServiceServer 的 A-2 模板。 */
    AgentCallbackClient(String baseUrl, String internalSecret, RestTemplate draftTemplate) {
        this.baseUrl = baseUrl;
        this.internalSecret = internalSecret;
        this.decomposeTemplate = buildTemplate(CONNECT_TIMEOUT_MS, 30_000);
        this.summaryTemplate = buildTemplate(CONNECT_TIMEOUT_MS, 60_000);
        this.draftTemplate = draftTemplate;
    }

    static RestTemplate buildTemplate(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Secret", internalSecret);
        return headers;
    }

    /**
     * B-1 AI 拆解。任何传输/解析异常包装后抛出（调用方 catch 后降级直建）。
     * 返回 decomposed=false 不算异常，同样由调用方走降级路径。
     */
    public DecomposeResult decompose(Long familyId, String title, String description, Long categoryId) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("title", title);
            body.put("description", description);
            body.put("category_id", categoryId);
            body.put("family_id", familyId);
            body.put("fallback", true);
            ResponseEntity<String> resp = decomposeTemplate.postForEntity(
                    baseUrl + "/api/internal/ai-decompose",
                    new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders()),
                    String.class);
            return objectMapper.readValue(resp.getBody(), DecomposeResult.class);
        } catch (Exception e) {
            throw new IllegalStateException("AI 拆解调用失败：" + e.getMessage(), e);
        }
    }

    /**
     * A-2 草稿模式拆解：把微信原文交给 agent-service，proposal 落
     * agent_decompose_sessions（B-6），**不写任何业务表**（D5 铁律）。
     *
     * <p>失败一律抛出 {@link IllegalStateException}（消息为中文，可直接进 last_error），
     * 覆盖三类：传输/解析失败、{@code code != 0}、{@code code == 0 但 subtasks 为空}。
     * 调用方（入站队列作业）按 D8 重试语义处理：attempts&lt;3 回 PENDING，耗尽标 FAILED。
     *
     * @param accessToken 该 openid 绑定用户的访问票据，agent-service 据此校验身份
     */
    public List<DraftSubtask> decomposeDraft(Long familyId, String accessToken, String message, Long sessionId) {
        String raw;
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("message", message);
            body.put("session_id", sessionId);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Family-Id", String.valueOf(familyId));
            headers.set("X-Access-Token", accessToken);
            ResponseEntity<String> resp = draftTemplate.postForEntity(
                    baseUrl + "/api/v1/chat",
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                    String.class);
            raw = resp.getBody();
        } catch (RestClientResponseException e) {
            throw draftHttpFailure(e);
        } catch (Exception e) {
            throw new IllegalStateException("草稿拆解调用失败：" + e.getMessage(), e);
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("草稿拆解响应解析失败：" + e.getMessage(), e);
        }
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("草稿拆解失败：" + root.path("msg").asText("服务未返回原因"));
        }
        JsonNode subtasks = root.path("data").path("subtasks");
        if (!subtasks.isArray() || subtasks.size() == 0) {
            throw new IllegalStateException("草稿拆解失败：没有拆出任何子任务");
        }
        List<DraftSubtask> result = new ArrayList<>();
        for (JsonNode node : subtasks) {
            result.add(new DraftSubtask(
                    node.hasNonNull("title") ? node.get("title").asText() : null,
                    node.hasNonNull("description") ? node.get("description").asText() : null,
                    node.hasNonNull("sort_order") ? node.get("sort_order").asInt() : null));
        }
        return result;
    }

    /**
     * 4xx/5xx 失败归因：agent-service 的鉴权/参数错误以 HTTP 401/400 +
     * {@code {"code":...,"msg":"中文"}} 返回（真机取证），故优先取响应体里的
     * 中文 msg；错误体不是该形状（网关 HTML、422 校验报文）时才落回状态行，
     * 避免把 "401 Unauthorized: [...]" 这类 Java 串当业务原因写进 last_error。
     */
    private IllegalStateException draftHttpFailure(RestClientResponseException e) {
        try {
            String msg = objectMapper.readTree(e.getResponseBodyAsString()).path("msg").asText("");
            if (!msg.isBlank()) {
                return new IllegalStateException("草稿拆解失败：" + msg, e);
            }
        } catch (Exception ignored) {
            // 错误体非 JSON：不额外包装，直接落回状态行
        }
        return new IllegalStateException("草稿拆解调用失败：" + e.getStatusText(), e);
    }

    /**
     * B-2 单家庭总结（Change 5 调度器为对端）。响应原文透传给调用方解析。
     * 单分类 60 秒超时由 agent-service 内部控制；此处整体读超时 60 秒兜底。
     */
    public JsonNode summarizeOneFamily(Long familyId, List<Long> categoryIds, String triggerSource) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("family_id", familyId);
            body.put("category_ids", categoryIds);
            body.put("timeout_per_category_ms", 60000);
            body.put("trigger_source", triggerSource == null ? "auto" : triggerSource);
            ResponseEntity<String> resp = summaryTemplate.postForEntity(
                    baseUrl + "/api/internal/summary/one-family",
                    new HttpEntity<>(objectMapper.writeValueAsString(body), jsonHeaders()),
                    String.class);
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("总结回调调用失败：" + e.getMessage(), e);
        }
    }
}
