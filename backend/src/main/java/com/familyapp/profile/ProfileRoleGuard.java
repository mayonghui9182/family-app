package com.familyapp.profile;

import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.common.ResultException;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 人员档案权限守卫
 *
 * 设计依据：plan Step 5 + spec §2 P-2/P-4/P-5 权限矩阵
 * - checkWriteAccess: CHILD 角色 POST/PUT → 40303「儿童无权限修改家庭档案」
 * - checkDeleteAccess: 非 OWNER 角色 DELETE → 40304「仅家庭主人可删除档案」
 * - checkDeleteReferenced: 档案有待办(PENDING)绑定 DELETE → 40901（Step 6 实现，依赖 TaskRepository 改造）
 *
 * 角色来源：UserContext.role（TestUserContextFilter 通过 X-Test-Role 头注入）
 */
@Component
public class ProfileRoleGuard {

    private final TaskRepository taskRepository;

    public ProfileRoleGuard(TaskRepository taskRepository) {
        this.taskRepository = taskRepository;
    }

    /** P-2 POST / P-4 PUT 前调用：CHILD 角色 → 40303 */
    public void checkWriteAccess(String role) {
        if ("CHILD".equals(role)) {
            throw new ResultException(40303, "儿童无权限修改家庭档案");
        }
    }

    /** P-5 DELETE 前调用：非 OWNER 角色 → 40304 */
    public void checkDeleteAccess(String role) {
        if (!"OWNER".equals(role)) {
            throw new ResultException(40304, "仅家庭主人可删除档案");
        }
    }

    /** P-5 DELETE 前调用：当前家庭内仍有待办或进行中任务时拒绝删除。 */
    public void checkDeleteReferenced(Long familyId, Long profileId) {
        long activeTaskCount = taskRepository
                .countByFamilyIdAndAssigneeProfileIdAndDeletedAtIsNullAndStatusIn(
                        familyId, profileId, List.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS));
        if (activeTaskCount > 0) {
            throw new ApiException(ResultCode.PROFILE_DELETE_HAS_TASKS);
        }
    }
}
