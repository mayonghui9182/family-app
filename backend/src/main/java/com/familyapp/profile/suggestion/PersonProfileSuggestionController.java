package com.familyapp.profile.suggestion;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * S-1：档案关联的任务/模板推荐（前端 SuggestionsPanel.vue 3 Tab 对应）
 * 路由完全匹配 frontend/src/api/suggestions.js：
 *   client.get(`/v1/person-profiles/${profileId}/suggestions`, {params:{categoryId}})
 *   → client.js 全局前缀 /api → /api/v1/person-profiles/{id}/suggestions
 *
 * 同其他档案/任务接口一致做 family_id 作用域校验：跨家庭 profileId 抛 404。
 */
@RestController
@RequestMapping("/api/v1/person-profiles")
public class PersonProfileSuggestionController {

    private final PersonProfileRepository profileRepo;
    private final PersonProfileSuggestionQueryService querySvc;

    public PersonProfileSuggestionController(PersonProfileRepository profileRepo,
                                             PersonProfileSuggestionQueryService querySvc) {
        this.profileRepo = profileRepo;
        this.querySvc = querySvc;
    }

    @GetMapping("/{profileId}/suggestions")
    public Result<Map<String, Object>> getSuggestions(
            @PathVariable("profileId") Long profileId,
            @RequestParam(value = "categoryId", required = false) Long categoryId) {
        UserContext.CurrentUser curr = UserContext.current();
        Long familyId = curr.familyId();
        if (familyId == null) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "缺少家庭上下文（X-Family-Id 头）");
        }
        PersonProfile profile = profileRepo.findByFamilyIdAndIdAndDeletedAtIsNull(familyId, profileId)
                .orElseThrow(() -> new ApiException(ResultCode.PROFILE_NOT_FOUND,
                        "人员档案不存在（id=" + profileId + "）"));

        Map<String, List<Map<String, Object>>> payload =
                querySvc.query3Tabs(profile, familyId, categoryId);

        // 兼容 suggestions.js 返回结构命名：{similarCompletedTasks, similarTemplates, pendingTasks}
        return Result.ok(Map.of(
                "similarCompletedTasks", payload.getOrDefault("similarCompletedTasks", List.of()),
                "similarTemplates", payload.getOrDefault("similarTemplates", List.of()),
                "pendingTasks", payload.getOrDefault("pendingTasks", List.of()),
                "_profile", Map.of(
                        "id", profile.getId(),
                        "name", profile.getName(),
                        "profileType", profile.getProfileType().name()
                )
        ));
    }
}
