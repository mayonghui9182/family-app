package com.familyapp.task;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyRepository;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.ProfileType;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.common.ResultException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * tasks.assignee_id / assignee_profile_id 双字段过渡兼容测试
 *
 * <p>验证对象：TaskServiceImpl.createTask 的过渡策略（spec §1.3 + plan §B 核心决策 2 + Global Constraints 第 2 条）
 *
 * <p>过渡策略三分支（plan Step 6 §TaskServiceImpl.createTask）：
 * <ol>
 *   <li>仅传 assigneeProfileId → 直接写入新列，assignee_id 留空</li>
 *   <li>仅传 assigneeId → 后端反查该 user 的 person_profile，自动回填 assignee_profile_id</li>
 *   <li>两列都传且指向不同人 → 40010「指派给谁参数冲突，请仅使用新档案字段」</li>
 * </ol>
 *
 * <p>双字段共存原则（plan Global Constraints 第 4 条）：
 * <ul>
 *   <li>过渡阶段不删除 tasks.assignee_id 列；Change 10 再处理</li>
 *   <li>CreateTaskRequest 同时保留 assigneeId 和 assigneeProfileId 字段</li>
 *   <li>TaskResponse 读 assignee_profile_id 新列优先，空 fallback 旧列反查 users.nickname</li>
 * </ul>
 *
 * <p>设计依据（plan Step 1c 第 5 项 + plan File Structure §TaskAssigneeProfileBackwardCompatTest）：
 * 3 用例：仅新列/仅旧列/两列不一致 40010
 *
 * <p>TDD 状态：
 * <ul>
 *   <li>RED 阶段（Step 1c）：Task.assigneeProfileId 字段 + PersonProfileRepository 不存在 → 编译失败 → 3 用例全 RED</li>
 *   <li>GREEN 阶段（Step 8）：Step 6 改造 Task 实体/Repository/Service + Step 5 PersonProfile 实体 → 全 PASS</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql") // family=1 user=1 category=2(育儿) 等种子，保证 FK 引用
class TaskAssigneeProfileBackwardCompatTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private PersonProfileRepository personProfileRepository;

    @Autowired
    private FamilyRepository familyRepository;

    // ==================== 用例 1：仅传 assigneeProfileId ====================

    @Test
    @DisplayName("1. 仅传 assigneeProfileId → task.assigneeProfileId 写入 + assigneeId 留空")
    void 仅传assigneeProfileId_写入新列() {
        // 预置：建一个 BABY 档案（family_id=1 默认家庭）
        PersonProfile profile = new PersonProfile();
        profile.setFamilyId(1L);
        profile.setProfileType(ProfileType.BABY);
        profile.setName("测试宝宝");
        profile.setAvatarEmoji("👶");
        profile.setRelation("儿子");
        profile.setCreatedBy(1L);
        PersonProfile saved = personProfileRepository.save(profile);

        // 仅传 assigneeProfileId，不传 assigneeId
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("喂奶");
        req.setFamilyId(1L);
        req.setCategoryId(2L); // 育儿分类
        req.setAssigneeProfileId(saved.getId());
        req.setCreatedBy(1L); // 非 HTTP 上下文直调 Service：显式指定创建人避免 UNAUTHORIZED
        // assigneeId 不设置（null）

        Task created = taskService.createTask(req);

        assertNotNull(created.getAssigneeProfileId(), "新列应被写入");
        assertEquals(saved.getId(), created.getAssigneeProfileId(), "新列值应等于传入的 profileId");
        assertNull(created.getAssigneeId(), "旧列应留空（仅传新列时）");
    }

    // ==================== 用例 2：仅传 assigneeId → 后端反查回填 ====================

    @Test
    @DisplayName("2. 仅传 assigneeId → 后端反查 user 对应的 person_profile，回填 assigneeProfileId")
    void 仅传assigneeId_后端反查回填新列() {
        // 预置：建一个 ADULT 档案关联 user_id=1（默认家庭的 owner 档案）
        PersonProfile profile = new PersonProfile();
        profile.setFamilyId(1L);
        profile.setUserId(1L); // 关联默认测试用户 user_id=1
        profile.setProfileType(ProfileType.ADULT);
        profile.setName("爸爸");
        profile.setAvatarEmoji("👨");
        profile.setRelation("本人");
        profile.setCreatedBy(1L);
        PersonProfile saved = personProfileRepository.save(profile);

        // 仅传 assigneeId=1，不传 assigneeProfileId
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("买菜");
        req.setFamilyId(1L);
        req.setCategoryId(1L); // 家务分类
        req.setAssigneeId(1L); // 旧字段
        req.setCreatedBy(1L); // 非 HTTP 上下文直调 Service：显式指定创建人
        // assigneeProfileId 不设置（null）

        Task created = taskService.createTask(req);

        assertEquals(1L, created.getAssigneeId(), "旧列应被写入");
        assertNotNull(created.getAssigneeProfileId(), "新列应被后端反查回填");
        assertEquals(saved.getId(), created.getAssigneeProfileId(),
            "回填的新列值应等于 user_id=1 对应的 profileId");
    }

    // ==================== 用例 3：两列不一致 → 40010 ====================

    @Test
    @DisplayName("3. 两列都传且指向不同人 → 40010「指派给谁参数冲突，请仅使用新档案字段」")
    void 两列不一致_抛40010() {
        // 预置：建两个不同的档案
        PersonProfile profile1 = new PersonProfile();
        profile1.setFamilyId(1L);
        profile1.setUserId(1L);
        profile1.setProfileType(ProfileType.ADULT);
        profile1.setName("爸爸");
        profile1.setAvatarEmoji("👨");
        profile1.setRelation("本人");
        profile1.setCreatedBy(1L);
        PersonProfile saved1 = personProfileRepository.save(profile1);

        PersonProfile profile2 = new PersonProfile();
        profile2.setFamilyId(1L);
        profile2.setProfileType(ProfileType.BABY);
        profile2.setName("小宝");
        profile2.setAvatarEmoji("👶");
        profile2.setRelation("儿子");
        profile2.setCreatedBy(1L);
        PersonProfile saved2 = personProfileRepository.save(profile2);

        // 两列都传且不一致：assigneeId=1（对应 profile1）+ assigneeProfileId=saved2.id（不同人）
        CreateTaskRequest req = new CreateTaskRequest();
        req.setTitle("冲突测试");
        req.setFamilyId(1L);
        req.setCategoryId(1L);
        req.setAssigneeId(1L);
        req.setAssigneeProfileId(saved2.getId()); // 与 assigneeId=1 对应的 profile1 不一致
        req.setCreatedBy(1L); // 非 HTTP 上下文直调 Service：显式指定创建人

        ResultException ex = assertThrows(ResultException.class, () -> taskService.createTask(req));
        assertEquals(40010, ex.getCode(), "应返回错误码 40010");
    }
}
