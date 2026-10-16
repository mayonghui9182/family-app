package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;
import com.familyapp.config.FeatureGuardConfig;
import com.familyapp.family.FamilyRole;
import com.familyapp.task.Task;
import com.familyapp.task.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Change 6 Task 1 Step 9：公开 Agent 代理层（合并 Change 7 D4 开关守卫）。
 * 路径前缀 /api/agent/v1/**，走公共拦截器：
 *   - AuthInterceptor（X-Access-Token 登录校验）
 *   - FamilyContextInterceptor（X-Family-Id 解析 + 成员校验，Change 6 已扩展路径到 /api/agent/**）
 *   - 不需要 X-Internal-Secret（仅 /api/internal/** 校验）。
 *
 * 已实现 10/10 端点：C-25/26 SSE + C-27/28/29/30/31 + C-32/33/34。
 * Change 7 D4：所有端点受 AI 拆解总开关守卫（关闭时 50310 中文提示），D-7 /config 除外。
 * 兼容保留 Change 7 POST /summary/trigger（转发 agent-service A-2，E2E smoke 依赖）。
 */
@RestController
@RequestMapping("/api/agent/v1")
public class AgentProxyController {

    private static final Logger log = LoggerFactory.getLogger(AgentProxyController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RuleTemplateRepository templateRepository;
    private final RuleTemplateVersionRepository versionRepository;
    private final SummaryLockService summaryLockService;
    private final SummaryExecutionLogRepository summaryLogRepository;
    private final TaskRepository taskRepository;
    private final FeatureGuardConfig featureGuard;

    @Value("${agent-service.base-url:http://localhost:8700}")
    private String agentServiceBaseUrl;

    /** SSE 专用 RestTemplate（长读超时 5 分钟，逐 chunk 不缓冲）。 */
    private final RestTemplate sseRestTemplate;

    public AgentProxyController(RuleTemplateRepository templateRepository,
                                RuleTemplateVersionRepository versionRepository,
                                SummaryLockService summaryLockService,
                                SummaryExecutionLogRepository summaryLogRepository,
                                TaskRepository taskRepository,
                                FeatureGuardConfig featureGuard) {
        this.templateRepository = templateRepository;
        this.versionRepository = versionRepository;
        this.summaryLockService = summaryLockService;
        this.summaryLogRepository = summaryLogRepository;
        this.taskRepository = taskRepository;
        this.featureGuard = featureGuard;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(300000);
        this.sseRestTemplate = new RestTemplate(factory);
    }

    // ==================== Change 7 D4：开关守卫 + D-7 /config ====================

    /**
     * D-7：前端开关状态端点（无需 token，WebConfig 已放行）。
     * 前端 stores/decompose.js 初始化时读取，决定是否隐藏 AI 按钮。
     */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        Map<String, Object> data = new HashMap<>();
        data.put("ai_decompose_enabled", featureGuard.isAiDecomposeEnabled());
        return Result.ok(data);
    }

    // ==================== C-25 SSE 拆解转发 ====================

    @PostMapping(value = "/decompose/start")
    public void decomposeStart(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        Result<JsonNode> g = featureGuard.guardAiEnabledSse();
        if (g != null) {
            // 开关关闭：返回 JSON 错误
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setStatus(ResultCode.AI_DECOMPOSE_DISABLED.getHttpStatus().value());
            try {
                response.getOutputStream().write(MAPPER.writeValueAsBytes(g));
                response.getOutputStream().flush();
            } catch (Exception e) {
                log.warn("写入 AI 开关关闭响应失败", e);
            }
            return;
        }
        Long familyId = currentFamilyId();
        if (body == null || !(body.get("session_id") instanceof Number id) || id.longValue() < 1) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "请先创建安排草稿，再开始拆解");
        }
        String url = agentServiceBaseUrl + "/api/v1/chat/stream";
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // 转发认证头到 agent-service
        String accessToken = httpRequest.getHeader("X-Access-Token");

        try {
            RequestCallback requestCallback = request -> {
                request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                request.getHeaders().set("X-Family-Id", String.valueOf(familyId));
                if (accessToken != null) {
                    request.getHeaders().set("X-Access-Token", accessToken);
                }
                if (body != null) {
                    request.getBody().write(MAPPER.writeValueAsBytes(body));
                }
            };
            ResponseExtractor<Void> responseExtractor = resp -> {
                try (InputStream is = resp.getBody()) {
                    byte[] buffer = new byte[4096];
                    int n;
                    var out = response.getOutputStream();
                    while ((n = is.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        out.flush();
                    }
                }
                return null;
            };
            sseRestTemplate.execute(url, HttpMethod.POST, requestCallback, responseExtractor);
        } catch (Exception e) {
            String errorEvent = "event: error\ndata: " + e.getMessage() + "\n\n";
            try {
                response.getOutputStream().write(errorEvent.getBytes(StandardCharsets.UTF_8));
                response.getOutputStream().flush();
            } catch (Exception ignored) {}
        }
    }

    // ==================== C-26 取消拆解 ====================

    @PostMapping("/decompose/{familyId}/{taskId}/cancel")
    public Result<Void> cancelDecompose(
            @PathVariable Long familyId,
            @PathVariable Long taskId) {
        featureGuard.guardAiEnabledOrThrow();
        Long ctxFamilyId = currentFamilyId();
        if (!familyId.equals(ctxFamilyId)) {
            throw new ApiException(ResultCode.FAMILY_NOT_MEMBER);
        }
        String url = agentServiceBaseUrl + "/api/v1/chat/cancel/" + familyId + "/" + taskId;
        try {
            sseRestTemplate.postForObject(url, null, Void.class);
        } catch (Exception e) {
            // 取消幂等：agent-service 无任务也视为成功
        }
        return Result.ok(null);
    }

    // ==================== C-27 模板列表（家庭隔离）====================

    @GetMapping("/templates")
    public Result<List<AgentApiDtos.TemplateResp>> getTemplates() {
        featureGuard.guardAiEnabledOrThrow();
        Long familyId = currentFamilyId();
        List<AgentApiDtos.TemplateResp> items = templateRepository
                .findByFamilyIdOrderByIdAsc(familyId)
                .stream()
                .map(this::toTemplateResp)
                .toList();
        return Result.ok(items);
    }

    private AgentApiDtos.TemplateResp toTemplateResp(RuleTemplate t) {
        return new AgentApiDtos.TemplateResp(
                t.getId(), t.getName(), t.getType(), t.getVersion(),
                t.getIsActive(), t.getConditions(), t.getContent(), t.getCategoryId());
    }

    // ==================== C-28 模板版本历史（真实 DB）====================

    @GetMapping("/templates/{id}/versions")
    public Result<AgentApiDtos.TemplateVersionsResp> getTemplateVersions(
            @PathVariable Long id) {
        featureGuard.guardAiEnabledOrThrow();
        Long familyId = currentFamilyId();
        List<RuleTemplateVersion> versions = versionRepository.findByTemplateIdOrderByVersionDesc(id);
        List<AgentApiDtos.TemplateVersionItem> items = versions.stream()
                .filter(v -> v.getFamilyId().equals(familyId))
                .limit(10)
                .map(this::toVersionItem)
                .toList();
        return Result.ok(new AgentApiDtos.TemplateVersionsResp(id, items));
    }

    private AgentApiDtos.TemplateVersionItem toVersionItem(RuleTemplateVersion v) {
        return new AgentApiDtos.TemplateVersionItem(
                v.getVersion(), v.getContent(), v.getChangeSummary(),
                v.getIsImportant(), v.getCreatedAt());
    }

    // ==================== C-29 PATCH star toggle ====================

    @PatchMapping("/templates/versions/{versionId}/star")
    public Result<Map<String, Object>> starVersion(@PathVariable Long versionId) {
        featureGuard.guardAiEnabledOrThrow();
        Long familyId = currentFamilyId();
        RuleTemplateVersion v = versionRepository.findByIdAndFamilyId(versionId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TEMPLATE_NOT_FOUND));
        v.setIsImportant(!v.getIsImportant());
        versionRepository.save(v);
        Map<String, Object> data = new HashMap<>();
        data.put("version_id", v.getId());
        data.put("is_important", v.getIsImportant());
        return Result.ok(data);
    }

    // ==================== C-30 DELETE version ====================

    @DeleteMapping("/templates/versions/{versionId}")
    public Result<Void> deleteVersion(@PathVariable Long versionId) {
        featureGuard.guardAiEnabledOrThrow();
        Long familyId = currentFamilyId();
        RuleTemplateVersion v = versionRepository.findByIdAndFamilyId(versionId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TEMPLATE_NOT_FOUND));
        versionRepository.delete(v);
        return Result.ok(null);
    }

    // ==================== C-31 POST pick ====================

    @PostMapping("/templates/{tplId}/versions/{versionId}/pick")
    public Result<Void> pickVersion(
            @PathVariable Long tplId,
            @PathVariable Long versionId,
            @RequestBody(required = false) Map<String, Object> body) {
        featureGuard.guardAiEnabledOrThrow();
        Long familyId = currentFamilyId();
        if (body == null || body.get("task_id") == null) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        versionRepository.findByIdAndFamilyId(versionId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TEMPLATE_NOT_FOUND));
        Long taskId;
        try {
            taskId = Long.valueOf(body.get("task_id").toString());
        } catch (NumberFormatException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        Task task = taskRepository.findByIdAndFamilyId(taskId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TASK_NOT_FOUND));
        task.setTargetTemplateVersionId(versionId);
        taskRepository.save(task);
        return Result.ok(null);
    }

    // ==================== C-32 手动触发总结 ====================

    @GetMapping("/summary/trigger/{familyId}")
    public Result<AgentApiDtos.SummaryClaimResp> triggerSummary(
            @PathVariable Long familyId) {
        featureGuard.guardAiEnabledOrThrow();
        Long ctxFamilyId = currentFamilyId();
        if (!familyId.equals(ctxFamilyId)) {
            throw new ApiException(ResultCode.FAMILY_NOT_MEMBER);
        }
        String role = UserContext.current().role();
        if (FamilyRole.CHILD.name().equals(role)) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION,
                    "小孩角色无权限手动触发总结");
        }
        try {
            SummaryExecutionLog row = summaryLockService.claim(familyId, "manual", false);
            return Result.ok(new AgentApiDtos.SummaryClaimResp(true, row.getId()));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ResultCode.SUMMARY_ALREADY_RAN);
        }
    }

    /**
     * Change 7 兼容端点：POST /summary/trigger 转发 agent-service A-2
     * （E2E smoke 脚本 Step8 依赖此路径断言 50310 降级）。
     */
    @PostMapping("/summary/trigger")
    public ResponseEntity<Result<JsonNode>> triggerSummaryForward(
            HttpServletRequest req,
            @RequestBody(required = false) Map<String, Object> body) {
        Result<JsonNode> g = featureGuard.guardAiEnabledSse();
        if (g != null) {
            return ResponseEntity.status(ResultCode.AI_DECOMPOSE_DISABLED.getHttpStatus()).body(g);
        }
        return forward(req, HttpMethod.POST, "/summary/trigger", body == null ? Map.of() : body);
    }

    // ==================== C-33 总结日志列表（真实 DB）====================

    @GetMapping("/summary/list/{familyId}")
    public Result<List<Map<String, Object>>> getSummaryList(@PathVariable Long familyId) {
        featureGuard.guardAiEnabledOrThrow();
        Long ctxFamilyId = currentFamilyId();
        if (!familyId.equals(ctxFamilyId)) {
            throw new ApiException(ResultCode.FAMILY_NOT_MEMBER);
        }
        List<SummaryExecutionLog> logs = summaryLogRepository.findByFamilyIdOrderByRunAtDesc(familyId);
        List<Map<String, Object>> items = logs.stream()
                .limit(20)
                .map(log -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", log.getId());
                    m.put("run_at", log.getRunAt());
                    m.put("status", log.getStatus());
                    m.put("trigger_source", log.getTriggerSource());
                    m.put("error_message", log.getErrorMessage());
                    return m;
                })
                .toList();
        return Result.ok(items);
    }

    // ==================== C-34 总结 3 指标聚合（真实 DB + Java 计算）====================

    @GetMapping("/summary/metrics/{familyId}")
    public Result<Map<String, Object>> getSummaryMetrics(@PathVariable Long familyId) {
        featureGuard.guardAiEnabledOrThrow();
        Long ctxFamilyId = currentFamilyId();
        if (!familyId.equals(ctxFamilyId)) {
            throw new ApiException(ResultCode.FAMILY_NOT_MEMBER);
        }
        Instant since = Instant.now().minus(30, ChronoUnit.DAYS);
        List<SummaryExecutionLog> logs = summaryLogRepository.findByFamilyIdOrderByRunAtDesc(familyId);

        long total30d = logs.stream()
                .filter(l -> l.getRunAt() != null && l.getRunAt().isAfter(since))
                .count();
        long success30d = logs.stream()
                .filter(l -> l.getRunAt() != null && l.getRunAt().isAfter(since)
                        && "success".equals(l.getStatus()))
                .count();
        int successRate = total30d == 0 ? 0 : (int) (success30d * 100 / total30d);

        long versionCount30d = versionRepository.findByFamilyId(familyId).stream()
                .filter(v -> v.getCreatedAt() != null && v.getCreatedAt().isAfter(since))
                .count();

        long learnedAdjustments = logs.stream()
                .filter(l -> l.getRunAt() != null && l.getRunAt().isAfter(since)
                        && "success".equals(l.getStatus())
                        && l.getCategoriesUpdated() != null
                        && l.getCategoriesUpdated().size() > 0)
                .mapToLong(l -> l.getCategoriesUpdated().size())
                .sum();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("success_rate_30d", successRate);
        metrics.put("version_count_30d", (int) versionCount30d);
        metrics.put("learned_adjustments_30d", (int) learnedAdjustments);
        return Result.ok(metrics);
    }

    // ==================== 工具方法 ====================

    private Long currentFamilyId() {
        Long fid = UserContext.current().familyId();
        if (fid == null) {
            throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED);
        }
        return fid;
    }

    /** Change 7 透传工具：转发 X-* 头到 agent-service（POST /summary/trigger 兼容端点专用）。 */
    private HttpHeaders buildForwardHeaders(HttpServletRequest req) {
        HttpHeaders h = new HttpHeaders();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.equalsIgnoreCase("Content-Type")) {
                String ct = req.getHeader(name);
                if (ct != null) h.setContentType(MediaType.parseMediaType(ct.split(";")[0].trim()));
            } else if (name.toLowerCase().startsWith("x-")) {
                h.set(name, req.getHeader(name));
            }
        }
        if (h.getContentType() == null) h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.ALL));
        return h;
    }

    private ResponseEntity<Result<JsonNode>> forward(HttpServletRequest req,
                                                     HttpMethod method,
                                                     String subPath,
                                                     Object body) {
        String url = agentServiceBaseUrl + "/api/v1" + subPath;
        HttpHeaders h = buildForwardHeaders(req);
        String bodyStr;
        try {
            bodyStr = (body == null) ? "" : (body instanceof String ? (String) body : MAPPER.writeValueAsString(body));
        } catch (Exception e) {
            log.warn("AgentProxy 序列化 body 失败 path={}", subPath, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error(ResultCode.BAD_REQUEST_VALIDATION, "请求体格式错误"));
        }
        HttpEntity<String> entity = new HttpEntity<>(bodyStr.isEmpty() ? null : bodyStr, h);
        try {
            ResponseEntity<String> resp = sseRestTemplate.exchange(url, method, entity, String.class);
            JsonNode data = parseNullable(resp.getBody());
            Result<JsonNode> r;
            if (data != null && data.has("code")) {
                int code = data.path("code").asInt(0);
                String msg = data.path("msg").asText("操作成功");
                JsonNode inner = data.get("data");
                r = new Result<>(code, msg, inner);
            } else {
                r = Result.ok(data);
            }
            return ResponseEntity.status(resp.getStatusCode()).body(r);
        } catch (HttpStatusCodeException sce) {
            JsonNode err = parseNullable(sce.getResponseBodyAsString());
            if (err != null && err.has("code")) {
                int code = err.path("code").asInt(50000);
                String defaultMsg = "下游服务响应异常（HTTP " + sce.getStatusCode().value() + "）";
                String msg = err.path("msg").asText(defaultMsg);
                return ResponseEntity.status(sce.getStatusCode()).body(new Result<>(code, msg, null));
            }
            log.warn("AgentProxy 转发异常 path={} status={}", subPath, sce.getStatusCode());
            return ResponseEntity.status(sce.getStatusCode())
                    .body(Result.error(ResultCode.SERVER_ERROR));
        } catch (Exception e) {
            log.warn("AgentProxy 转发失败 path={}", subPath, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Result.error(ResultCode.SERVER_ERROR, "AI 服务暂不可用，请稍后重试"));
        }
    }

    private static JsonNode parseNullable(String s) {
        if (s == null || s.isBlank()) return null;
        try { return MAPPER.readTree(s); } catch (Exception e) { return null; }
    }
}
