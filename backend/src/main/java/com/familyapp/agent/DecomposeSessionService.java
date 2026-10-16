package com.familyapp.agent;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.profile.Discard;
import com.familyapp.profile.PersonProfileService;
import com.familyapp.profile.PersonProfileService.PatchResult;
import com.familyapp.task.TaskServiceImpl;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DecomposeSessionService {

    private static final int PROPOSED_TASKS_LIMIT = 20;

    private final DecomposeSessionRepository sessionRepo;
    private final PersonProfileService personProfileService;
    private final TaskServiceImpl taskService;

    public DecomposeSessionService(DecomposeSessionRepository sessionRepo,
                                   PersonProfileService personProfileService,
                                   TaskServiceImpl taskService) {
        this.sessionRepo = sessionRepo;
        this.personProfileService = personProfileService;
        this.taskService = taskService;
    }

    // ==================== D-1：创建拆解草稿会话（只写 agent_decompose_sessions，不写业务表） ====================

    /**
     * 从前端 decomposeSession.js createSession 传入的 session_type + init_payload 构建草稿会话并落库。
     * 草稿状态=ACTIVE（D-1~D-4 可被 D-5/D-6/D-7 终态处理），family_id + created_by 强制由 UserContext 注入（不采信 body）。
     *
     * @param sessionTypeStr 枚举名：TASK_CREATE（任务页拆解）或 PROFILE_INTERVIEW（档案页 AI 访谈）
     * @param initPayload 前端 TaskView.vue.decomposeInitForm → {title,description,dueDate,priority,categoryId,assigneeProfileId}
     * @param familyId 当前家庭（X-Family-Id 经拦截器校验后注入）
     * @param actorUserId 当前用户（经拦截器鉴权后注入）
     * @return 落库后的 DecomposeSession（Controller 将其转换为 Result<{session_id}> 返回前端）
     */
    @Transactional
    public DecomposeSession createDraft(String sessionTypeStr,
                                        Map<String, Object> initPayload,
                                        Long familyId,
                                        Long actorUserId) {
        // 1) session_type 枚举校验（非法值 → 400 BAD_REQUEST_VALIDATION）
        DecomposeSessionType st;
        try {
            st = DecomposeSessionType.valueOf(sessionTypeStr.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,
                    "session_type 非法，应为 TASK_CREATE 或 PROFILE_INTERVIEW（传入: " + sessionTypeStr + "）");
        }

        DecomposeSession s = new DecomposeSession();
        s.setFamilyId(familyId);
        s.setCreatedBy(actorUserId);
        s.setSessionType(st);
        s.setStatus(DecomposeSessionStatus.ACTIVE);

        // 2) init_payload → Entity 列名映射（前端用驼峰，这里做一次显式转换）
        Map<String, Object> p = initPayload != null ? initPayload : Map.of();
        Object title = p.get("title");
        if (title instanceof String s1 && !s1.isBlank()) {
            s.setInitTaskTitle(s1.length() > 200 ? s1.substring(0, 200) : s1);
        } else if (st == DecomposeSessionType.PROFILE_INTERVIEW) {
            s.setInitTaskTitle("AI完善档案访谈");
        } else {
            s.setInitTaskTitle("未命名 AI 拆解任务");
        }
        Object desc = p.get("description");
        if (desc instanceof String s1) s.setInitTaskDescription(s1);
        Object pri = p.get("priority");
        if (pri instanceof String s1) s.setInitTaskPriority(s1.length() > 10 ? s1.substring(0, 10) : s1);
        Object catId = p.get("categoryId");
        s.setInitTaskCategoryId(castToLongOrNull(catId));
        Object assigneePid = p.get("assigneeProfileId");
        s.setProfileId(castToLongOrNull(assigneePid));
        Object due = p.get("dueDate");
        if (due instanceof String s1 && !s1.isBlank()) {
            try { s.setInitTaskDueDate(LocalDate.parse(s1, DateTimeFormatter.ISO_LOCAL_DATE)); }
            catch (DateTimeParseException ignored) { /* 前端表单值，失败就不填，不阻断会话创建 */ }
        } else if (due instanceof LocalDate ld) {
            s.setInitTaskDueDate(ld);
        }
        // init_task_id：如果已经有任务再拆解（路由 /chat?taskId=xxx 场景），从 init_payload.taskId 读
        Object taskId = p.get("taskId");
        s.setInitTaskId(castToLongOrNull(taskId));

        // init_profile_id_snapshot：传了就原样保留（例如 {target: profileId}），后面 R4 patch 里 resolveFirstProfileTarget 会读
        Object snap = p.get("initProfileIdSnapshot");
        if (snap instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) m;
            s.setInitProfileIdSnapshot(new HashMap<>(typed));
        } else if (s.getProfileId() != null) {
            // assigneeProfileId 传了就 snapshot 至少给个 target，让 resolveFirstProfileTarget 能找到
            Map<String, Object> def = new HashMap<>();
            def.put("target", s.getProfileId());
            s.setInitProfileIdSnapshot(def);
        }

        // messages = []（空 list，D-2 SSE 沟通后才写）
        s.setMessages(new ArrayList<>());
        s.setExtractedProfilePatch(new HashMap<>());
        s.setProposedTasks(new ArrayList<>());
        return sessionRepo.save(s);
    }

    @Transactional
    public DecomposeConfirmResp confirm(Long sessionId, Long familyId, Long actorUserId) {
        DecomposeSession s = loadActiveScoped(sessionId, familyId);

        List<Map<String, Object>> proposed = castToMapList(s.getProposedTasks());
        if (proposed.isEmpty()) {
            throw new ApiException(ResultCode.AI_PROPOSAL_EMPTY);
        }
        if (proposed.size() > PROPOSED_TASKS_LIMIT) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,
                    "拆解结果超过 " + PROPOSED_TASKS_LIMIT + " 条，请让 AI 精简后再确认");
        }
        if (s.getSessionType() == DecomposeSessionType.PROFILE_INTERVIEW) {
            boolean hasAnyProfileTarget = resolveFirstProfileTarget(s) != null;
            boolean hasPatch = s.getExtractedProfilePatch() != null && !s.getExtractedProfilePatch().isEmpty();
            if (hasPatch && !hasAnyProfileTarget) {
                throw new ApiException(ResultCode.AI_PROPOSAL_NO_CONTEXT,
                        "PROFILE_INTERVIEW 会话缺少目标档案，无法合并 patch");
            }
        }

        List<Long> mergedProfileIds = new ArrayList<>();
        List<String> discardedKeys = new ArrayList<>();
        applyExtractedPatchToLinkedProfiles(s, actorUserId, mergedProfileIds, discardedKeys);

        List<Long> createdTaskIds = new ArrayList<>();
        List<Object> savedTasks = new ArrayList<>();
        for (Map<String, Object> p : proposed) {
            CreateTaskRequest req = toCreateTaskRequest(p, actorUserId, familyId);
            TaskResponse created = taskService.create(req);
            createdTaskIds.add(created.id());
            Map<String, Object> saved = new HashMap<>(p);
            saved.put("created_task_id", created.id());
            savedTasks.add(saved);
        }

        s.setProposedTasks(savedTasks);
        s.setStatus(DecomposeSessionStatus.CONFIRMED);
        sessionRepo.save(s);

        return new DecomposeConfirmResp(
                List.copyOf(mergedProfileIds),
                List.copyOf(discardedKeys),
                List.copyOf(createdTaskIds)
        );
    }

    @Transactional
    public DecomposeSaveOnlyResp saveProfileOnly(Long sessionId, Long familyId, Long actorUserId) {
        DecomposeSession s = loadActiveScoped(sessionId, familyId);

        List<Long> merged = new ArrayList<>();
        List<String> discarded = new ArrayList<>();
        applyExtractedPatchToLinkedProfiles(s, actorUserId, merged, discarded);

        s.setStatus(DecomposeSessionStatus.ARCHIVED);
        sessionRepo.save(s);
        return new DecomposeSaveOnlyResp(List.copyOf(merged), List.copyOf(discarded));
    }

    @Transactional
    public void cancel(Long sessionId, Long familyId, Long actorUserId) {
        DecomposeSession s = loadActiveScoped(sessionId, familyId);
        s.setStatus(DecomposeSessionStatus.CANCELLED);
        sessionRepo.save(s);
    }

    // ==================== D-8：按家庭作用域读取草稿详情（Change 14 Task 11） ====================

    /**
     * 微信免密深链点进 H5 草稿详情页时的只读入口。
     * 与 loadActiveScoped 的区别：写路径（confirm/cancel/save-profile-only）只许 ACTIVE；
     * 读路径放开到 ACTIVE/CONFIRMED/CANCELLED——用户重复点同一链接必须看到真实状态
     * （「已创建过」「已不要了」），而不是 404 让人误以为链接坏了。
     * ARCHIVED（save-profile-only 的中间归档态）不在草稿详情展示 → 50311（话术原文含「归档」）。
     * 不存在或跨家庭 → 46001（与越权同码，不区分「不存在/别人的」，防枚举）。
     */
    @Transactional(readOnly = true)
    public DecomposeDetailResp readScoped(Long sessionId, Long familyId) {
        DecomposeSession s = sessionRepo.findByIdAndFamilyId(sessionId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.AI_PROPOSAL_NOT_FOUND,
                        "session id=" + sessionId + " 不存在或不属于当前家庭"));
        DecomposeSessionStatus st = s.getStatus();
        if (st == DecomposeSessionStatus.ARCHIVED) {
            throw new ApiException(ResultCode.AI_PROPOSAL_ALREADY_FINALIZED);
        }
        List<Object> tasks = s.getProposedTasks() == null ? List.of() : List.copyOf(s.getProposedTasks());
        return new DecomposeDetailResp(
                s.getId(),
                st.name(),
                s.getSessionType() == null ? null : s.getSessionType().name(),
                s.getInitTaskTitle(),
                tasks,
                s.getMessages() == null ? List.of() : List.copyOf(s.getMessages())
        );
    }

    @Transactional
    public void updateDraft(Long id, Long familyId, List<Object> tasks) {
        DecomposeSession s = loadActiveScoped(id, familyId);
        if (tasks == null || tasks.isEmpty() || tasks.size() > PROPOSED_TASKS_LIMIT) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "请保留 1 至 " + PROPOSED_TASKS_LIMIT + " 条待办");
        }
        if (castToMapList(tasks).size() != tasks.size()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "草稿格式不正确，请重新生成");
        }
        for (Map<String, Object> task : castToMapList(tasks)) {
            Object title = task.get("title");
            if (!(title instanceof String t) || t.isBlank() || t.length() > 200) {
                throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "每条待办需要 1 至 200 字的标题");
            }
        }
        s.setProposedTasks(new ArrayList<>(tasks));
        sessionRepo.save(s);
    }

    private DecomposeSession loadActiveScoped(Long sessionId, Long familyId) {
        DecomposeSession s = sessionRepo.findLockedScoped(sessionId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.AI_PROPOSAL_NOT_FOUND,
                        "session id=" + sessionId + " 不存在或不属于当前家庭"));
        if (s.getStatus() != DecomposeSessionStatus.ACTIVE) {
            throw new ApiException(ResultCode.AI_PROPOSAL_ALREADY_FINALIZED);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private void applyExtractedPatchToLinkedProfiles(DecomposeSession s,
                                                      Long actorUserId,
                                                      List<Long> mergedProfileIds,
                                                      List<String> discardedKeys) {
        Map<String, Object> extracted = s.getExtractedProfilePatch();
        if (extracted == null || extracted.isEmpty()) return;

        Long fallbackTarget = resolveFirstProfileTarget(s);

        if (extracted.get("patches") instanceof List<?> patchesList) {
            for (Object row : patchesList) {
                if (!(row instanceof Map<?, ?> m)) continue;
                Map<String, Object> patchRow = (Map<String, Object>) m;
                Long pid = extractProfileId(patchRow, fallbackTarget);
                if (pid == null) continue;
                mergeOneProfile(s.getFamilyId(), pid, patchRow, mergedProfileIds, discardedKeys);
            }
        } else {
            Long pid = extractProfileId(extracted, fallbackTarget);
            if (pid == null) return;
            mergeOneProfile(s.getFamilyId(), pid, extracted, mergedProfileIds, discardedKeys);
        }
    }

    private Long resolveFirstProfileTarget(DecomposeSession s) {
        if (s.getProfileId() != null) return s.getProfileId();
        Map<String, Object> snap = s.getInitProfileIdSnapshot();
        if (snap != null) {
            Object target = snap.get("target");
            return castToLongOrNull(target);
        }
        return null;
    }

    private Long castToLongOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String str && !str.isBlank()) {
            try { return Long.parseLong(str); }
            catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private Long extractProfileId(Map<String, Object> source, Long fallback) {
        Object raw = source.get("profile_id");
        Long pid = castToLongOrNull(raw);
        return (pid != null) ? pid : fallback;
    }

    @SuppressWarnings("unchecked")
    private void mergeOneProfile(Long familyId,
                                 Long pid,
                                 Map<String, Object> patchRow,
                                 List<Long> mergedProfileIds,
                                 List<String> discardedKeys) {

        // 1. 从 patchRow 分离：结构性 key（profile_id / remove_keys） vs 真实 profile_data patch
        List<String> removeKeys = List.of();
        Object rawRemoveKeys = patchRow.get("remove_keys");
        if (rawRemoveKeys instanceof List<?> rk) {
            List<String> typed = new ArrayList<>();
            for (Object o : rk) {
                if (o instanceof String s) typed.add(s);
            }
            removeKeys = typed;
        }
        Map<String, Object> purePatch = new HashMap<>(patchRow);
        purePatch.remove("profile_id");
        purePatch.remove("remove_keys");

        try {
            PatchResult pr = personProfileService.applyProfilePatch(familyId, pid, purePatch, removeKeys);
            for (Discard d : pr.discardedKeys()) discardedKeys.add(d.key());
        } catch (ApiException e) {
            if (e.getCode() == ResultCode.PROFILE_NOT_FOUND) {
                discardedKeys.add("profile_id:" + pid + ":NOT_FOUND");
                return;
            }
            throw e;
        }
        mergedProfileIds.add(pid);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castToMapList(List<Object> rawList) {
        if (rawList == null) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(rawList.size());
        for (Object o : rawList) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }

    private CreateTaskRequest toCreateTaskRequest(Map<String, Object> proposal, Long actorUserId, Long familyId) {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle((String) proposal.getOrDefault("title", ""));
        Object desc = proposal.get("description");
        if (desc instanceof String s) req.setDescription(s);
        Object dd = proposal.get("due_date");
        if (dd instanceof String s && !s.isBlank()) {
            try {
                req.setDueDate(LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE));
            } catch (DateTimeParseException e) {
                throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,
                        "任务 due_date 格式无效: \"" + s + "\"，应为 yyyy-MM-dd");
            }
        } else if (dd instanceof LocalDate ld) {
            req.setDueDate(ld);
        }
        Object prio = proposal.get("priority");
        if (prio instanceof String s) req.setPriority(s);
        Object st = proposal.get("status");
        if (st instanceof String s) req.setStatus(s);
        Object src = proposal.get("source");
        if (src instanceof String s) req.setSource(s);
        Object cid = proposal.get("category_id");
        if (cid instanceof Number n) req.setCategoryId(n.longValue());
        else if (cid instanceof String str && !str.isBlank()) {
            try { req.setCategoryId(Long.parseLong(str)); } catch (NumberFormatException ignored) {}
        }
        Object aid = proposal.get("assignee_id");
        if (aid instanceof Number n) req.setAssigneeId(n.longValue());
        else if (aid instanceof String str && !str.isBlank()) {
            try { req.setAssigneeId(Long.parseLong(str)); } catch (NumberFormatException ignored) {}
        }
        Object apid = proposal.get("assignee_profile_id");
        if (apid instanceof Number n) req.setAssigneeProfileId(n.longValue());
        else if (apid instanceof String str && !str.isBlank()) {
            try { req.setAssigneeProfileId(Long.parseLong(str)); } catch (NumberFormatException ignored) {}
        }
        req.setFamilyId(familyId);
        req.setCreatedBy(actorUserId);
        return req;
    }

    public record DecomposeConfirmResp(
            List<Long> merged_profile_ids,
            List<String> discarded_keys,
            List<Long> created_task_ids
    ) {}

    public record DecomposeSaveOnlyResp(
            List<Long> merged_profile_ids,
            List<String> discarded_keys
    ) {}

    /**
     * D-8 草稿详情响应。命名沿用 D-1/D-5 既有的 snake_case 响应体惯例。
     * 规格原文（design.md D-7 / tasks.md Step1-①）里的 createdTaskIds[] 未包含：
     * 该数据在当前 schema 无落库来源（confirm 中是局部变量，仅出现在 D-5 响应体），
     * 「已创建」态由 status=CONFIRMED 驱动，已在 tasks.md Task 11 偏离清单记账。
     */
    public record DecomposeDetailResp(
            Long id,
            String status,
            String session_type,
            String init_task_title,
            List<Object> proposed_tasks,
            List<Object> messages
    ) {}
}
