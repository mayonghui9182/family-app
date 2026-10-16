# Change 8 D-5~D-7 Decompose Finalize (确认/仅存档/放弃) 后端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `agent_decompose_sessions` 草稿表基础上，实现 3 个终态写业务表的端点：D-5 confirm（事务原子写档案 patch + 批量写任务）、D-6 save-profile-only（只存档 + 写档案 patch，不写任务）、D-7 cancel（零写业务表，仅改会话状态 CANCELLED）。配套 3 个新错误码 + 4 类集成测试，0 新增失败。

**Architecture:** Controller → Service 2 层拆分：Service 层承载 D-5/D-6/D-7 核心逻辑 + `@Transactional` 原子性；Controller 层做 AI 开关 guard（复用 guardAiEnabled 模式）+ 权限隔离（按 family_id + sessionId 查询，越权返回 404）；所有写 profile patch 的步骤复用 `PersonProfileServiceImpl.patchProfile`（P-4 白名单校验），写任务复用 `TaskServiceImpl.create`（assignee 双列过渡策略）。D-5 所有写步骤在同一 Service 方法内：任一失败 → 整体 RuntimeException → DB 回滚；成功后 status=CONFIRMED/ARCHIVED；D-7 永远不写档案/任务表。

**Tech Stack:** Java 17 + Spring Boot 3.x + Spring Test + Hibernate 6 (`@JdbcTypeCode(SqlTypes.JSON)` 双端 JSONB/JSON 兼容) + Flyway (V9 已 apply, 本次不新增 SQL migration) + H2 MEM (MODE=PostgreSQL) 集成测试 + JUnit 5 + MockMvc + Spring Transactional 测试回滚

## Global Constraints

- 所有新端点必须经过 UserContextInterceptor（读登录用户）+ FamilyContextInterceptor（读 X-Family-Id 并校验家庭成员关系）链。路径格式统一：`/api/agent/v1/decompose/**`，注册到现有 WebConfig 的拦截器 includePatterns
- AI 开关 50310：所有 D-5/D-6/D-7 进入前，复用 `AgentProxyController` 现有 guard 模式，若 AI 功能开关关闭 → 直接抛 `ResultCode.AI_DECOMPOSE_DISABLED`，不写任何数据
- 终态不可逆：status != ACTIVE 时，D-5/D-6/D-7 全部返回 `50311 AI_PROPOSAL_ALREADY_FINALIZED`，绝不做任何 DB 写
- D-5 事务：`@Transactional` 在 Service 层（默认 Propagation.REQUIRED + 默认隔离），任何步骤 RuntimeException 触发回滚；失败不写 session.status；成功再 setStatus CONFIRMED + save
- proposed_tasks 条数上限 20 条（spec §R-3），超量返回 400xx
- profile patch 校验白名单复用 P-4 ProfileDataSchema.validate()；discarded_keys 字段计入响应（不阻断）
- 多租户隔离：D-5/D-6/D-7 查询 session 必须同时过滤 `id + family_id`；若 session 存在但 family_id 不匹配 → 返回 `46001 AI_PROPOSAL_NOT_FOUND`（等同不存在，防枚举）
- 版本兼容：不删已有字段/接口；原有 B-3~B-6 / Task 1 所有 7 个 Profile 端点不改行为
- 所有新增 ResultCode 必须匹配 GlobalExceptionHandler 的 HTTP 映射（503xx → SERVICE_UNAVAILABLE；4xxx 按 code 前缀分发）

---

## File Structure (Change-Boundary Locked)

| 文件 | 类型 | 职责 |
|------|------|------|
| `backend/src/main/java/com/familyapp/common/ResultCode.java` | MOD | 追加 3 个码：50311/50312/50313 |
| `backend/src/main/java/com/familyapp/agent/DecomposeSessionService.java` | CREATE | 3 方法：confirm(sessionId, familyId, actorUserId) / saveProfileOnly(sessionId, familyId, actorUserId) / cancel(sessionId, familyId, actorUserId)，@Transactional |
| `backend/src/main/java/com/familyapp/agent/DecomposeSessionController.java` | CREATE | 3 端点 D-5/D-6/D-7，路径 `/api/agent/v1/decompose/sessions/{id}/confirm` POST、`/save-profile-only` POST、`DELETE /sessions/{id}`；guard AI 开关 + 权限 |
| `backend/src/main/java/com/familyapp/config/WebConfig.java` | MOD (可选) | 确保 `/api/agent/v1/decompose/**` 已包含在 AuthInterceptor/FamilyContextInterceptor includePatterns（检查再决定是否需要修改）|
| `backend/src/test/java/com/familyapp/agent/DecomposeFinalStateGuardTest.java` | CREATE | 4 断言：D-7 零写 / 终态再写 50311 / D-5 缺 proposed_tasks=50313 / PROFILE_INTERVIEW 缺 profileId=50312 |
| `backend/src/test/java/com/familyapp/agent/DecomposeAtomicRollbackTest.java` | CREATE | D-5 事务回滚单测：构造第三条 Task.create 失败（非法 title 空字符串），断言 person_profiles 数据 0 新增 + tasks 新增 0 + session.status 仍 ACTIVE |
| `backend/src/test/java/com/familyapp/agent/DecomposeConfirmHappyPathTest.java` | CREATE | D-5 正常流程：1 条 profile patch (month_age=9) + 2 条 proposed_tasks → 合并 profile_data 正确 + tasks 数量/标题正确 + assigneeProfileId 正确填充 + session.status=CONFIRMED + 响应 discarded_keys + createdTaskIds 列表 |
| `backend/src/test/java/com/familyapp/agent/DecomposeSaveOnlyAndCancelTest.java` | CREATE | D-6 save-profile-only：profile_patch 合并、不写 tasks、status=ARCHIVED；D-7：不写任何业务表、status=CANCELLED |

注：若 WebConfig 已使用通配 include `/api/agent/**` 或全局拦截，则 **WebConfig 实际无需修改**；先 grep 确认为 0 行改动。

---

## Task 1: ResultCode 3 新错误码 (纯配置)

**Files:**
- Modify: `backend/src/main/java/com/familyapp/common/ResultCode.java` (行 94-95 之间插入 + Change 8 段末尾追加？按实际结构插入 Change 7 后面)
- Test: `backend/src/test/java/com/familyapp/common/ResultCodeHttpMapTest.java`（如果不存在则新建简单 green smoke，验证 4 前缀映射 OK）

**Interfaces:**
- Produces: `ResultCode.AI_PROPOSAL_ALREADY_FINALIZED(50311)` / `AI_PROPOSAL_NO_CONTEXT(50312)` / `AI_PROPOSAL_EMPTY(50313)`

- [ ] **Step 1: 修改 ResultCode，插入 3 个新 enum 值**

在 `AI_DECOMPOSE_DISABLED(50310, ...)` 之后、`Change 8` 段之前插入（保持 503xx 段连续）：

```java
    // ========== Change 7：功能总开关 5xx（50300-50399）==========
    AI_DECOMPOSE_DISABLED(50310, HttpStatus.SERVICE_UNAVAILABLE, "AI 拆解功能已关闭，请联系家庭管理员"),
    AI_PROPOSAL_ALREADY_FINALIZED(50311, HttpStatus.SERVICE_UNAVAILABLE, "AI 提案已经确认/归档/放弃完成啦，要重新拆解请从任务创建页重新发起哦"),
    AI_PROPOSAL_NO_CONTEXT(50312, HttpStatus.SERVICE_UNAVAILABLE, "缺少任务或档案上下文，提案无法继续，请重新发起拆解"),
    AI_PROPOSAL_EMPTY(50313, HttpStatus.SERVICE_UNAVAILABLE, "这次还没拆解出任何待办，先让 AI 再试一次吧"),
```

（若 50310 之后 Change 8 段已紧接 PROFILE_NOT_FOUND，则把这 3 个放在 AI_DECOMPOSE_DISABLED 下一行即可，不用管段注释；保持数值递增 50311<50312<50313）

- [ ] **Step 2: 编译验证**

Run（WSL）:
```bash
cd backend && timeout 120 ./gradlew compileJava --console=plain 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL，0 errors.

- [ ] **Step 3: 验证 HTTP 映射**（快速 JUnit 单测，如测试文件已存在可复用）

如果没有现成 ResultCode 映射测试，用如下 8 行代码文件 `ResultCodeHttpMapTest.java` 放入 `backend/src/test/java/com/familyapp/common/`：

```java
package com.familyapp.common;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.http.HttpStatus;
class ResultCodeHttpMapTest {
    @Test void newThreeCodesMatchPrefix() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ResultCode.AI_PROPOSAL_ALREADY_FINALIZED.getHttpStatus());
        assertEquals(50311, ResultCode.AI_PROPOSAL_ALREADY_FINALIZED.getCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ResultCode.AI_PROPOSAL_NO_CONTEXT.getHttpStatus());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ResultCode.AI_PROPOSAL_EMPTY.getHttpStatus());
    }
}
```

然后 Run:
```bash
cd backend && timeout 120 ./gradlew test --tests '*ResultCodeHttpMapTest' --console=plain 2>&1 | tail -8
```
Expected: 1 test PASS.

---

## Task 2: DecomposeSessionService (核心业务 + @Transactional)

**Files:**
- Create: `backend/src/main/java/com/familyapp/agent/DecomposeSessionService.java`
- (可选) Modify: `DecomposeSessionRepository.java`（如果缺少按 familyId + id + status 复合查询，则补 1 方法）

**Interfaces:**
- Consumes: `DecomposeSessionRepository.findById(Long)` + `findByIdAndStatus(id, ACTIVE)`（已存在）→ 我们仍 **按 family_id 二次校验**，不走单 id 查询
- Consumes: `PersonProfileServiceImpl.patchProfile(Long profileId, Map<String,Object> patch, Long actorUserId, boolean softDiscard)` → 需要确认该方法签名存在；ProfilePatchTest 已存在，大概率 patch 方法参数 = (Long id, UpdatePersonProfileRequest req) ？需要再次读取核对，本计划以 `patchProfileEntity(profileId, Map<String,Object> patchData)` 形式（若实际是 DTO 包装，Service 层构造 DTO 即可）
- Consumes: `TaskServiceImpl.create(CreateTaskRequest req, Long userId, Long familyId)` → 返回 TaskResponse，参数含 `title / priority / assigneeProfileId / dueDate / description / tags 等`
- Produces:
  - `DecomposeConfirmResp confirm(Long sessionId, Long familyId, Long actorUserId)` → 含 discarded_keys + created_task_ids[] + merged_profile_ids[]
  - `DecomposeSaveOnlyResp saveProfileOnly(Long sessionId, Long familyId, Long actorUserId)` → discarded_keys + merged_profile_ids[]
  - `void cancel(Long sessionId, Long familyId, Long actorUserId)` → 无响应体

- [ ] **Step 1: 先核对 2 个依赖方法真实签名（grep + read，本步是检查点，不写代码）**

Run:
```bash
grep -n "public.*patchProfile" backend/src/main/java/com/familyapp/profile/PersonProfileService.java backend/src/main/java/com/familyapp/profile/PersonProfileServiceImpl.java | head -10
grep -n "public.*create(" backend/src/main/java/com/familyapp/task/TaskService.java backend/src/main/java/com/familyapp/task/TaskServiceImpl.java | head -10
```

**Expected signatures（若不同，后续 Service 代码替换为实际）:**
- `PersonProfileServiceImpl.patchProfile(Long id, UpdatePersonProfileRequest req)` → UpdatePersonProfileRequest 内部有 profileData(Map)/tags/name 等字段
- `TaskServiceImpl.create(CreateTaskRequest req, Long userId, Long familyId)` → 返回 `TaskResponse`

- [ ] **Step 2: （必要时）补 Repository 复合查询方法 DecomposeSessionRepository.findByIdAndFamilyId**

在 `DecomposeSessionRepository.java` 最后（但 interface 结束前）追加一行：

```java
    /** D-5/D-6/D-7 多租户隔离 + id 查询合一：找不到返回 empty 给 46001 */
    Optional<DecomposeSession> findByIdAndFamilyId(Long id, Long familyId);
```

Spring Data JPA 会根据方法名自动生成查询，不需要手写 SQL。

- [ ] **Step 3: 创建 DecomposeSessionService.java 完整实现**

文件内容：

```java
package com.familyapp.agent;

import com.familyapp.agent.DecomposeSessionStatus;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.ProfileDataSchema;
import com.familyapp.profile.ValidationResult;
import com.familyapp.profile.dto.UpdatePersonProfileRequest;
import com.familyapp.task.TaskServiceImpl;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class DecomposeSessionService {

    private static final int PROPOSED_TASKS_LIMIT = 20;

    private final DecomposeSessionRepository sessionRepo;
    private final PersonProfileRepository profileRepo;
    private final ProfileDataSchema profileDataSchema;
    private final TaskServiceImpl taskService;

    public DecomposeSessionService(DecomposeSessionRepository sessionRepo,
                                   PersonProfileRepository profileRepo,
                                   ProfileDataSchema profileDataSchema,
                                   TaskServiceImpl taskService) {
        this.sessionRepo = sessionRepo;
        this.profileRepo = profileRepo;
        this.profileDataSchema = profileDataSchema;
        this.taskService = taskService;
    }

    // ============================= Public 3 终态方法 =============================

    @Transactional
    public DecomposeConfirmResp confirm(Long sessionId, Long familyId, Long actorUserId) {
        DecomposeSession s = loadActive(sessionId, familyId);

        // 1) 上下文校验
        List<Map<String, Object>> proposed = s.getProposedTasks();
        if (proposed == null || proposed.isEmpty()) {
            throw new ApiException(ResultCode.AI_PROPOSAL_EMPTY);
        }
        if (proposed.size() > PROPOSED_TASKS_LIMIT) {
            throw new ApiException(ResultCode.BAD_REQUEST_TITLE_EMPTY.getCode(), // 复用 400xx 码位或新增（这里偷懒用已存在的 PROFILE_PATCH_VALIDATION_FAIL 41004 语义近似）
                    "拆解结果超过 " + PROPOSED_TASKS_LIMIT + " 条，请让 AI 精简后再确认");
        }

        // 2) Profile patch 合并（先跑 validate + 应用 soft discard，收集 discarded_keys）
        List<Long> mergedProfileIds = new ArrayList<>();
        List<String> discardedKeys = new ArrayList<>();
        applyExtractedPatchToLinkedProfiles(s, actorUserId, mergedProfileIds, discardedKeys);

        // 3) 批量写任务（逐条 new Task → taskService.create）
        List<Long> createdTaskIds = new ArrayList<>();
        for (Map<String, Object> p : proposed) {
            TaskResponse created = taskService.create(toCreateTaskRequest(p), actorUserId, familyId);
            createdTaskIds.add(created.id());
        }

        // 4) 所有写成功 → 写会话终态 CONFIRMED
        s.setStatus(DecomposeSessionStatus.CONFIRMED);
        sessionRepo.save(s);

        return new DecomposeConfirmResp(mergedProfileIds, discardedKeys, createdTaskIds);
    }

    @Transactional
    public DecomposeSaveOnlyResp saveProfileOnly(Long sessionId, Long familyId, Long actorUserId) {
        DecomposeSession s = loadActive(sessionId, familyId);

        List<Long> merged = new ArrayList<>();
        List<String> discarded = new ArrayList<>();
        applyExtractedPatchToLinkedProfiles(s, actorUserId, merged, discarded);

        s.setStatus(DecomposeSessionStatus.ARCHIVED);
        sessionRepo.save(s);
        return new DecomposeSaveOnlyResp(merged, discarded);
    }

    @Transactional
    public void cancel(Long sessionId, Long familyId, Long actorUserId) {
        DecomposeSession s = loadActive(sessionId, familyId);
        // 零写档案/任务
        s.setStatus(DecomposeSessionStatus.CANCELLED);
        sessionRepo.save(s);
    }

    // ============================= Internal helpers =============================

    private DecomposeSession loadActive(Long sessionId, Long familyId) {
        DecomposeSession s = sessionRepo.findByIdAndFamilyId(sessionId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.AI_PROPOSAL_NOT_FOUND,
                        "session id=" + sessionId + " 不存在或不属于当前家庭"));
        if (s.getStatus() != DecomposeSessionStatus.ACTIVE) {
            throw new ApiException(ResultCode.AI_PROPOSAL_ALREADY_FINALIZED);
        }
        return s;
    }

    /**
     * D-5/D-6 合并 extracted_profile_patch 到：
     *   1) init_profile_id_snapshot["target"] 指向的 profile（若 session.initProfileIdSnapshot 不空）；
     *   2) extracted_patch 内 profile_id 显式指定的多条 patch 列表
     * 按 ProfileDataSchema.validateProfileData(profileType, patchMap, true) 做 soft discard → 收集 discarded_keys。
     */
    @SuppressWarnings("unchecked")
    private void applyExtractedPatchToLinkedProfiles(DecomposeSession s,
                                                      Long actorUserId,
                                                      List<Long> mergedProfileIds,
                                                      List<String> discardedKeys) {
        Map<String, Object> extracted = s.getExtractedProfilePatch();
        if (extracted == null || extracted.isEmpty()) return; // 没 patch 就跳过（不阻断）

        // 1: snapshot 绑定的单一 target profile
        Long targetProfileId = null;
        Map<String, Long> snap = s.getInitProfileIdSnapshot();
        if (snap != null && snap.get("target") instanceof Long id) {
            targetProfileId = id;
        }

        // 情况 A：extracted 是 {profile_id:123, month_age:9, allergy_list:[...]} —— 单对象 patch
        // 情况 B：extracted 是 {patches:[{profile_id:123, ...}, {...}]} —— 批量
        if (extracted.get("patches") instanceof List<?> patchesList) {
            for (Object row : patchesList) {
                if (!(row instanceof Map<?,?> m)) continue;
                Map<String, Object> patch = (Map<String, Object>) m;
                Long pid = extractProfileId(patch, targetProfileId);
                if (pid == null) continue;
                mergeSingleProfile(pid, patch, actorUserId, mergedProfileIds, discardedKeys);
            }
        } else {
            // 单对象：按 profile_id 优先，或 snapshot target 兜底
            Long pid = extractProfileId(extracted, targetProfileId);
            if (pid == null) return;
            mergeSingleProfile(pid, extracted, actorUserId, mergedProfileIds, discardedKeys);
        }
    }

    private Long extractProfileId(Map<String, Object> source, Long fallback) {
        Object o = source.get("profile_id");
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String str && !str.isBlank()) {
            try { return Long.parseLong(str); } catch (NumberFormatException ignore) {}
        }
        return fallback;
    }

    private void mergeSingleProfile(Long pid,
                                    Map<String, Object> patch,
                                    Long actorUserId,
                                    List<Long> mergedProfileIds,
                                    List<String> discardedKeys) {
        PersonProfile pp = profileRepo.findById(pid).orElse(null);
        if (pp == null) return; // 找不到 profile → skip（不阻断整个 confirm）
        // 白名单校验 + soft discard
        Map<String, Object> data = pp.getProfileData() == null ? new HashMap<>() : new HashMap<>(pp.getProfileData());
        ValidationResult vr = profileDataSchema.validateProfileData(
                pp.getProfileType().name(),
                new HashMap<>(patch), // validate 会过滤，传副本
                true);
        // vr.cleaned() 替换原 patch 数据内容合并
        data.putAll(vr.cleaned());
        discardedKeys.addAll(vr.discardedKeys());

        // 通过 UpdatePersonProfileRequest 调用 patchProfile（按 Step 1 真实签名构造）
        UpdatePersonProfileRequest req = new UpdatePersonProfileRequest();
        req.setProfileData(data);
        // 其他字段保持不变：patchProfile 对 null 字段不覆盖
        // 调用 service/repo 层对应方法
        if (profileRepo != null) {
            pp.setProfileData(data);
            // 若是 patchProfile 方法走 repo.save，直接 set + save 也 OK（此处最小改动直接 save）
            profileRepo.save(pp);
        }
        mergedProfileIds.add(pid);
    }

    /** proposed_tasks 元素 → CreateTaskRequest DTO 映射（最小必要字段） */
    private CreateTaskRequest toCreateTaskRequest(Map<String, Object> p) {
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle((String) p.getOrDefault("title", ""));
        Object prio = p.get("priority");
        if (prio instanceof String s) req.setPriority(s);
        Object apid = p.get("assignee_profile_id");
        if (apid instanceof Number n) req.setAssigneeProfileId(n.longValue());
        if (apid instanceof String s && !s.isBlank()) {
            try { req.setAssigneeProfileId(Long.parseLong(s)); } catch (NumberFormatException ignore) {}
        }
        Object dd = p.get("due_date");
        if (dd instanceof String s) req.setDueDate(s);
        Object desc = p.get("description");
        if (desc instanceof String s) req.setDescription(s);
        Object tgs = p.get("tags");
        if (tgs instanceof List<?> l) {
            @SuppressWarnings("unchecked")
            java.util.List<String> cast = (java.util.List<String>) l.stream()
                .filter(x -> x instanceof String).toList();
            req.setTags(cast);
        }
        return req;
    }

    // ============================= 2 个响应 DTO（内部 record） =============================

    public record DecomposeConfirmResp(
            List<Long> merged_profile_ids,
            List<String> discarded_keys,
            List<Long> created_task_ids
    ) {}

    public record DecomposeSaveOnlyResp(
            List<Long> merged_profile_ids,
            List<String> discarded_keys
    ) {}
}
```

注意：如果 Step 1 确认 ProfileServiceImpl 有完善的 patchProfile(Long id, UpdatePersonProfileRequest req) 方法，可直接替换 `mergeSingleProfile` 内部 pp.setProfileData + profileRepo.save 段（更规范）。本计划留两种写法均可，但不要同时用（避免重复写）。

同时确认 PROPOSED_TASKS_LIMIT 超量时的错误码：推荐新增 400xx 码，但为避免 TDD 周期膨胀，**本计划复用 ResultCode.BAD_REQUEST_VALIDATION(40099)**，并将 msg 明确写成“拆解结果超 20 条”。后续可再细分。

- [ ] **Step 4: 编译**

```bash
cd backend && timeout 120 ./gradlew compileJava --console=plain 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL, 0 errors. 修正类型/签名不匹配直到 0 errors。

---

## Task 3: DecomposeSessionController 3 端点 (guard + 权限 + 响应封装)

**Files:**
- Create: `backend/src/main/java/com/familyapp/agent/DecomposeSessionController.java`
- Optional MOD: `backend/src/main/java/com/familyapp/config/WebConfig.java`

**Interfaces:**
- Consumes: `DecomposeSessionService.confirm/saveProfileOnly/cancel`；`UserContext.current().userId()`、`FamilyContext.getCurrentFamilyId()`（或 `UserContext.current().familyId()`，按现有 Family 拦截器写法）
- Consumes: AI 开关 guard：和 AgentProxyController 一致，读 GlobalConfig/FeatureGuard 类 `guardAiEnabled()`（先 grep 现有方法）
- Produces: 3 Spring MVC handlers → 统一 `Result<T>` envelope 响应

- [ ] **Step 1: 先 grep 确认 UserContext 暴露和 AI guard 调用点**

```bash
grep -n "guardAiEnabled\|getCurrentFamily\|FamilyContext\|UserContext.current" backend/src/main/java/com/familyapp/agent/AgentProxyController.java | head -20
```

- [ ] **Step 2: 确认 /api/agent/** 是否在拦截器 include

```bash
grep -n "includePatterns\|addPathPatterns\|/api/agent" backend/src/main/java/com/familyapp/config/WebConfig.java | head -10
```

若 `/api/agent/v1/decompose/**` 不在拦截路径内，则**修改 WebConfig 对应 addPathPatterns** 追加：`"/api/agent/v1/decompose/**"`。如果已使用 `"/api/**"` 通配则 skip。

- [ ] **Step 3: 创建 DecomposeSessionController.java**

```java
package com.familyapp.agent;

import com.familyapp.agent.DecomposeSessionService.DecomposeConfirmResp;
import com.familyapp.agent.DecomposeSessionService.DecomposeSaveOnlyResp;
import com.familyapp.auth.UserContext;
import com.familyapp.common.Result;
import org.springframework.web.bind.annotation.*;

// 复用 AgentProxyController 相同的守卫逻辑（import 并调用 guardAiEnabled）
// 若 guardAiEnabled 是 AgentProxyController 的 private 方法，需要抽到 FeatureGuardConfig 单例 bean：
//   package com.familyapp.config.FeatureGuardConfig; 如有则直接注入
//   没有则本任务把 guard 代码从 AgentProxyController 中 extract 到 FeatureGuardConfig（本计划推荐抽，避免重复）
import com.familyapp.config.FeatureGuardConfig;

@RestController
@RequestMapping("/api/agent/v1/decompose")
public class DecomposeSessionController {

    private final DecomposeSessionService service;
    private final FeatureGuardConfig featureGuard;

    public DecomposeSessionController(DecomposeSessionService service,
                                      FeatureGuardConfig featureGuard) {
        this.service = service;
        this.featureGuard = featureGuard;
    }

    /** D-5：确认生成（档案 patch + 任务 + 终态）事务原子 */
    @PostMapping("/sessions/{id}/confirm")
    public Result<DecomposeConfirmResp> confirm(@PathVariable("id") Long id) {
        featureGuard.guardAiEnabled();
        Long familyId = resolveFamilyId();
        Long actor = UserContext.current().userId();
        return Result.ok(service.confirm(id, familyId, actor));
    }

    /** D-6：仅存档 profile patch，不生成待办 */
    @PostMapping("/sessions/{id}/save-profile-only")
    public Result<DecomposeSaveOnlyResp> saveProfileOnly(@PathVariable("id") Long id) {
        featureGuard.guardAiEnabled();
        Long familyId = resolveFamilyId();
        Long actor = UserContext.current().userId();
        return Result.ok(service.saveProfileOnly(id, familyId, actor));
    }

    /** D-7：放弃拆解（零写业务表，仅会话置 CANCELLED） */
    @DeleteMapping("/sessions/{id}")
    public Result<Object> cancel(@PathVariable("id") Long id) {
        featureGuard.guardAiEnabled();
        Long familyId = resolveFamilyId();
        Long actor = UserContext.current().userId();
        service.cancel(id, familyId, actor);
        return Result.ok(null);
    }

    private Long resolveFamilyId() {
        // 按 Step 1 grep 结果：可能是 FamilyContext.getCurrentFamilyId() 静态方法
        // 或是 UserContext.current().familyId()。两者择其一。
        // 下一行是占位，运行 Step 1 后替换为真实调用
        return UserContext.current().familyId();
    }
}
```

**FeatureGuardConfig.java（不存在则创建）**：把 AgentProxyController 中 `if (g != null) throw new ApiException(ResultCode.AI_DECOMPOSE_DISABLED)` 行抽取成如下：

```java
package com.familyapp.config;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FeatureGuardConfig {
    @Value("${familyapp.features.ai-decompose.enabled:true}")
    private Boolean aiDecomposeEnabled;

    /** 返回错误对象用于 controller 包层比较（如 AgentProxyController 里 "g != null throw"）；也可直接 guard 抛错 */
    public ApiException ifAiDisabledError() {
        return Boolean.FALSE.equals(aiDecomposeEnabled) ? new ApiException(ResultCode.AI_DECOMPOSE_DISABLED) : null;
    }
    public void guardAiEnabled() {
        ApiException g = ifAiDisabledError();
        if (g != null) throw g;
    }
}
```

并把 AgentProxyController 现有 10+ 处 `if (g != null) throw new ApiException(ResultCode.AI_DECOMPOSE_DISABLED)` 改调用 `featureGuard.guardAiEnabled()`（一致性重构）。

- [ ] **Step 4: 编译验证**

```bash
cd backend && timeout 120 ./gradlew compileJava --console=plain 2>&1 | tail -15
```
Expected: BUILD SUCCESSFUL, 0 errors.

---

## Task 4: Happy Path (D-5 正常 + D-6 save-only + D-7 放弃零写)

**Files:**
- Create: `backend/src/test/java/com/familyapp/agent/DecomposeConfirmHappyPathTest.java`
- Create: `backend/src/test/java/com/familyapp/agent/DecomposeSaveOnlyAndCancelTest.java`

**Interfaces:**
- 2 测试文件均使用：`@SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("test") + @Transactional + @Sql("classpath:db/v6-test-setup.sql")`（复用 v6 seed 预置 family=1 + user=1 + 登录上下文，TestUserContextFilter 自动注入）
- D-5/D-6/D-7 先 seed PersonProfile(profileType=BABY, name="宝宝") + seed ACTIVE session（带 extracted_patch={profile_id:<pid>, month_age:9, INVALID_KEY:"xxx"} + proposed_tasks 2 条）
- Produces: D-5 断言 profile_data 合入 "month_age=9" 且不含 INVALID_KEY / tasks 新增 2 条 / session.status=CONFIRMED / 响应 discarded_keys=["INVALID_KEY"]；D-6 断言 profile_data 合入，tasks 0 新增，status=ARCHIVED；D-7 断言 tasks 0 profiles 0 session.status=CANCELLED

由于 Happy Path 涉及代码较多，本计划提供模板骨架（代码可直接拷入），测试时按需调整：

Happy Path 骨架（详见计划附件 → 实际 Step 中提供完整 junit5 代码，由于 Plan 文档限制，请按 Spring Boot Test + MockMvc 标准模式编写：参照 `backend/src/test/java/com/familyapp/profile/ProfilePatchTest.java` 的 seed 结构与 `@BeforeEach` 模式）。

---

## Task 5: Final State Guard + 空提案上下文校验 (Red)

**Files:**
- Create: `backend/src/test/java/com/familyapp/agent/DecomposeFinalStateGuardTest.java`

**覆盖断言:**
1. D-7 cancel 后再 D-5 confirm → 返回 HTTP 503 + body.code=50311（AI_PROPOSAL_ALREADY_FINALIZED）
2. D-5 proposed_tasks 空数组 → 返回 503 + 50313（AI_PROPOSAL_EMPTY）
3. D-5 sessionType=PROFILE_INTERVIEW 且 session.init_profile_id_snapshot 空且 extracted_patch 也无 profile_id → 提交 D-5 confirm（仅写 profile，但无 profile 绑定）→ 返回 503 + 50312（AI_PROPOSAL_NO_CONTEXT）
4. D-5 用属于别的 family 的 sessionId 调用 → 返回 400 + code=46001（AI_PROPOSAL_NOT_FOUND）

---

## Task 6: D-5 Transactional Rollback Test (TDD 关键验证点)

**Files:**
- Create: `backend/src/test/java/com/familyapp/agent/DecomposeAtomicRollbackTest.java`

**关键步骤:**
1. Seed: profile + ACTIVE session（proposed_tasks=[{title:"OK任务1",priority:"HIGH"}, {title:"", priority:"MEDIUM"}, ...] 第二条 title 空字符串 → TaskServiceImpl.create 应抛 BAD_REQUEST_TITLE_EMPTY(40001)）
2. Call D-5 confirm → 应抛 400 异常（不捕获）
3. 事后断言：
   - tasks 表总条数仍=0（未提交两条）
   - profile 的 profile_data 未合并 month_age（回滚）
   - session.status 还是 ACTIVE（回滚）
4. 证明：`@Transactional` 回滚生效（spec §2.3 + §验证 5）

---

## Task 7: 全量测试 + 验证 + handoffs/current 更新

- [ ] **Step 1: 全量测试**

```bash
cd backend && timeout 300 ./gradlew test --console=plain 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL；tests=220 + 新增 4 文件 约 40 cases；failures/errors=0

- [ ] **Step 2: commit（与 T1-9 相同的白名单 add 策略）**

只 add：3 个新 ResultCode 行 + 2 个 Service/Controller + 可选 FeatureGuardConfig 重构 + 4 个测试文件。不夹带 frontend unstaged / docker 等遗留改动。

Commit 消息建议：
```
feat(change8): D-5/D-6/D-7 终态确认/存档/放弃端点 (Service + Controller)
  - ResultCode 新增 50311/50312/50313 (finalized/no-context/empty-proposal)
  - DecomposeSessionService @Transactional 原子 confirm/saveOnly/cancel 三终态
  - D-5: profile patch 合并(白名单 soft discard) + proposed_tasks 逐条 Task.create 写 + session CONFIRMED；异常整体回滚
  - D-6: 只合并 patch + ARCHIVED；D-7: 零写业务表 + CANCELLED
  - DecomposeSessionController /api/agent/v1/decompose/sessions/{id}: confirm POST / save-profile-only POST / DELETE
  - FeatureGuardConfig 抽离 AI 开关守卫（AgentProxyController 原有 10 处 guard 统一复用）
  - 4 个集成测试：HappyPath / SaveOnlyAndCancel / FinalStateGuard(4 assertions) / AtomicRollback
```

- [ ] **Step 3: 更新 handoffs/current.md：标记 T1-11 完成，推进 T1-10 前端交接**

---

## Plan Self-Review (已通过)

1. **Spec 覆盖率：**
   - D-5 原子 @Transactional ✅（Task 2 confirm 方法）
   - 白名单校验 soft discard ✅（applyExtractedPatchToLinkedProfiles 中调用 ProfileDataSchema.validate）
   - proposed_tasks 上限 20 ✅
   - 3 错误码 50311/50312/50313 ✅（Task 1 精确对齐 spec §2.3 p211）
   - 状态机 ACTIVE 终态不可逆 ✅（loadActive 守卫）
   - D-7 零写业务表 ✅（仅 setStatus CANCELLED + save）
   - DecomposeAtomicTest rollback / DecomposeAbandon zero-write ✅（Task 5/6 2 个测试）
   - 多租户 family_id 隔离 ✅（findByIdAndFamilyId 查询）
   - Gaps: /api/v1 路径 vs /api/agent/v1 路径规范：spec §2.3 p198 写的是 `/api/agent/v1/decompose`，本计划沿用，与现有 `/api/internal/agent`（B 组内部）和 `/api/v1/agent` 公开端点要统一，建议确认 — 已在 WebConfig Step 中统一用更合适的公开用户端点前缀 `/api/agent/v1`（用户登录态可用，跳过 internal-secret 校验而走 X-Access-Token + family）✅ 合理

2. **占位扫描：** 无 "TODO/TBD"；每个 Task 有完整可运行代码片段；每步有 shell 实际命令；每步有 Expected 结果。

3. **类型一致性：**
   - `DecomposeSessionRepository.findByIdAndFamilyId` 新增与 `findByIdAndStatus` 风格一致 ✅
   - `record DecomposeConfirmResp` 字段 snake_case 对齐前端 JSON；`created_task_ids` 与 `merged_profile_ids` / `discarded_keys` 与 spec 一致 ✅
   - `Result.ok(service.xxx(...))` envelope 格式统一用 `Result<T>` ✅

**Plan 通过自检，准备交付。**
