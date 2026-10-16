package com.familyapp.task;

import com.familyapp.profile.PersonProfile;
import org.springframework.data.jpa.domain.Specification;

/** 本人任务统一口径：有效档案优先，无档案的历史任务才使用旧账号字段。 */
public final class TaskAssignees {
    private TaskAssignees() { }

    public static Specification<Task> forUser(Long familyId, Long userId) {
        return (root, query, cb) -> {
            var profiles = query.subquery(Long.class);
            var profile = profiles.from(PersonProfile.class);
            profiles.select(profile.get("id")).where(
                    cb.equal(profile.get("familyId"), familyId),
                    cb.equal(profile.get("userId"), userId),
                    cb.isNull(profile.get("deletedAt")));
            return cb.and(cb.equal(root.get("familyId"), familyId), cb.or(
                    root.get("assigneeProfileId").in(profiles),
                    cb.and(cb.isNull(root.get("assigneeProfileId")),
                            cb.equal(root.get("assigneeId"), userId))));
        };
    }
}
