package com.familyapp.agent;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;
import com.familyapp.config.FeatureGuardConfig;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/agent/v1/decompose/sessions")
public class DecomposeSessionController {

    private final DecomposeSessionService service;
    private final FeatureGuardConfig featureGuard;

    public DecomposeSessionController(DecomposeSessionService service,
                                      FeatureGuardConfig featureGuard) {
        this.service = service;
        this.featureGuard = featureGuard;
    }

    // ==================== D-1：创建拆解草稿会话（前端 stores/decomposeSse.js D-1 onInit → createSession 调用） ====================

    /**
     * 接收 { session_type, init_payload } → 写 agent_decompose_sessions ACTIVE 草稿 → 返回 { session_id }。
     * 与 D-5/D-6/D-7 共用同一张 sessions 表 + FeatureGuard AI 拆解总开关守卫（关闭时 50310）。
     * 会话创建完成后，前端立即切换到 D-2 聊天 SSE（/agent/v1/decompose/start，走 AgentProxyController 转发 agent-service）
     * 或 Mock 剧本（chatStreamMock + decomposeStreamMock）。
     */
    @PostMapping("")
    public Result<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        featureGuard.guardAiEnabledOrThrow();
        UserContext.CurrentUser curr = UserContext.current();
        Long familyId = requireFamilyId(curr);

        Map<String, Object> req = body != null ? body : Map.of();
        String sessionType = (String) req.getOrDefault("session_type", DecomposeSessionType.TASK_CREATE.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> initPayload = (Map<String, Object>) req.getOrDefault("init_payload", Map.of());

        DecomposeSession saved = service.createDraft(sessionType, initPayload, familyId, curr.userId());
        return Result.ok(Map.of(
                "session_id", saved.getId(),
                "status", saved.getStatus().name(),
                "session_type", saved.getSessionType().name()
        ));
    }

    // ==================== D-8：按家庭作用域读取草稿详情（Change 14 Task 11，微信免密深链落地页数据源） ====================

    /**
     * GET /{id} → Result&lt;{id,status,session_type,init_task_title,proposed_tasks}&gt;。
     * 三段式守卫与写端点一致：AI 总开关（50310）→ 当前用户 → family_id（40041）。
     * 作用域与状态策略在 service.readScoped：读路径放开 ACTIVE/CONFIRMED/CANCELLED，ARCHIVED → 50311。
     */
    @GetMapping("/{id}")
    public Result<DecomposeSessionService.DecomposeDetailResp> detail(@PathVariable Long id) {
        featureGuard.guardAiEnabledOrThrow();
        UserContext.CurrentUser curr = UserContext.current();
        Long familyId = requireFamilyId(curr);
        return Result.ok(service.readScoped(id, familyId));
    }

    /** 用户修改草稿，只保存草稿内容，不创建任务。 */
    @PutMapping("/{id}/proposal")
    public Result<Void> updateProposal(@PathVariable Long id, @RequestBody java.util.List<Object> tasks) {
        featureGuard.guardAiEnabledOrThrow();
        service.updateDraft(id, requireFamilyId(UserContext.current()), tasks);
        return Result.ok(null);
    }

    @PostMapping("/{id}/confirm")
    public Result<DecomposeSessionService.DecomposeConfirmResp> confirm(@PathVariable Long id) {
        featureGuard.guardAiEnabledOrThrow();
        UserContext.CurrentUser curr = UserContext.current();
        Long familyId = requireFamilyId(curr);
        return Result.ok(service.confirm(id, familyId, curr.userId()));
    }

    @PostMapping("/{id}/save-profile-only")
    public Result<DecomposeSessionService.DecomposeSaveOnlyResp> saveProfileOnly(@PathVariable Long id) {
        featureGuard.guardAiEnabledOrThrow();
        UserContext.CurrentUser curr = UserContext.current();
        Long familyId = requireFamilyId(curr);
        return Result.ok(service.saveProfileOnly(id, familyId, curr.userId()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> cancel(@PathVariable Long id) {
        featureGuard.guardAiEnabledOrThrow();
        UserContext.CurrentUser curr = UserContext.current();
        Long familyId = requireFamilyId(curr);
        service.cancel(id, familyId, curr.userId());
        return Result.ok(null);
    }

    private Long requireFamilyId(UserContext.CurrentUser curr) {
        Long fid = curr.familyId();
        if (fid == null) {
            throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED);
        }
        return fid;
    }
}
