package com.familyapp.profile.suggestion;

import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.ProfileType;
import com.familyapp.task.Task;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskStatus;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * S-1 查询逻辑（只读，不写事务）。
 *
 * 3 Tab 与前端 SuggestionsPanel 完全对齐：
 *   1) similarCompletedTasks：当前家庭下，已完成(COMPLETED) + 指派给 profile_type 相同成员的任务（含 categoryId 过滤）
 *   2) similarTemplates：无专门模板表 → 基于 similarCompletedTasks 的标题去重后 TOP 3 推荐作「历史模板」
 *   3) pendingTasks：当前家庭下，未完成(PENDING/IN_PROGRESS/OVERDUE) + 指派给本成员 OR 同类型成员的任务
 *
 * 每条结果转成轻量 Map（id/title/description/priority/status/dueDate/categoryId/assigneeProfileId/createdAt），
 * 避免引入新 DTO 类（防 drift）。
 */
@Service
public class PersonProfileSuggestionQueryService {

    private static final int TAB_LIMIT = 15;
    private static final int TEMPLATE_LIMIT = 3;

    private final TaskRepository taskRepo;
    private final PersonProfileRepository profileRepo;

    public PersonProfileSuggestionQueryService(TaskRepository taskRepo,
                                               PersonProfileRepository profileRepo) {
        this.taskRepo = taskRepo;
        this.profileRepo = profileRepo;
    }

    public Map<String, List<Map<String, Object>>> query3Tabs(PersonProfile targetProfile,
                                                             Long familyId,
                                                             Long categoryIdOpt) {
        ProfileType targetType = targetProfile.getProfileType();

        // (0) 预取：同家庭下 profile_type=targetType 的全部 profile_id 集合（作用域内，跨家庭天然不匹配）
        List<Long> sameTypeProfileIds = profileRepo
                .findByFamilyIdAndProfileTypeAndDeletedAtIsNull(familyId, targetType)
                .stream()
                .map(PersonProfile::getId)
                .filter(Objects::nonNull)
                .toList();

        // (1) similarCompletedTasks：COMPLETED + sameTypeProfileIds ∋ assigneeProfileId + (categoryId 传了就匹配)
        List<Task> completed = findBy(
                familyId,
                Set.of(TaskStatus.COMPLETED),
                sameTypeProfileIds,
                categoryIdOpt
        );
        List<Map<String, Object>> completedView = completed.stream()
                .sorted(Comparator.comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(TAB_LIMIT)
                .map(this::taskView)
                .toList();

        // (2) similarTemplates：completed 标题去重 TOP TEMPLATE_LIMIT（无专门模板表兜底方案）
        LinkedHashSet<String> titleDedup = new LinkedHashSet<>();
        List<Map<String, Object>> tplView = new ArrayList<>();
        for (Task t : completed) {
            if (t.getTitle() == null) continue;
            String normalized = t.getTitle().strip();
            if (normalized.isEmpty() || !titleDedup.add(normalized)) continue;
            String tplId = "tpl-" + Integer.toHexString(normalized.hashCode());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", tplId);
            row.put("title", normalized);
            row.put("categoryId", t.getCategoryId());
            row.put("priority", t.getPriority() != null ? t.getPriority().name() : null);
            row.put("source", "COMPLETED_HISTORY");
            row.put("lastCompletedAt", t.getUpdatedAt() != null ? t.getUpdatedAt().toString() : null);
            tplView.add(row);
            if (tplView.size() >= TEMPLATE_LIMIT) break;
        }

        // (3) pendingTasks：未完成态（PENDING / IN_PROGRESS）+ (assigneeProfileId=target 或 sameTypeProfileIds 包含)
        // 注：本项目 TaskStatus 枚举无 OVERDUE（过期待办仍在 PENDING，由 due_date 在前端红显）。
        List<Task> pending = findPending(
                familyId,
                Set.of(TaskStatus.PENDING, TaskStatus.IN_PROGRESS),
                sameTypeProfileIds,
                targetProfile.getId(),
                categoryIdOpt
        );
        List<Map<String, Object>> pendingView = pending.stream()
                .sorted(Comparator
                        .comparing((Task t) -> t.getDueDate() == null ? 1 : 0, Comparator.naturalOrder())
                        .thenComparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Task::getPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(TAB_LIMIT)
                .map(this::taskView)
                .toList();

        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("similarCompletedTasks", completedView);
        out.put("similarTemplates", tplView);
        out.put("pendingTasks", pendingView);
        return out;
    }

    // ======================== 查询：JPQL 不现成，改内存过滤（TAB_LIMIT 小数据量无性能问题） ========================

    private List<Task> findBy(Long familyId,
                              Set<TaskStatus> statusSet,
                              List<Long> sameTypeProfileIds,
                              Long categoryIdOpt) {
        // 先取 family 下状态匹配的全部（JPQL findWithFilters），再 in-memory 切 profileId
        List<Task> pool = new ArrayList<>();
        for (TaskStatus s : statusSet) {
            pool.addAll(taskRepo.findWithFilters(familyId, s, categoryIdOpt, null, null, null));
        }
        Set<Long> sameType = new HashSet<>(sameTypeProfileIds);
        return pool.stream()
                .filter(t -> t.getAssigneeProfileId() != null && sameType.contains(t.getAssigneeProfileId()))
                .collect(Collectors.toList());
    }

    private List<Task> findPending(Long familyId,
                                   Set<TaskStatus> statuses,
                                   List<Long> sameTypeProfileIds,
                                   Long targetProfileId,
                                   Long categoryIdOpt) {
        List<Task> pool = new ArrayList<>();
        for (TaskStatus s : statuses) {
            pool.addAll(taskRepo.findWithFilters(familyId, s, categoryIdOpt, null, null, null));
        }
        Set<Long> sameType = new HashSet<>(sameTypeProfileIds);
        return pool.stream()
                .filter(t -> {
                    Long ap = t.getAssigneeProfileId();
                    if (ap == null) return false;
                    // target 本人 or 同类型
                    return ap.equals(targetProfileId) || sameType.contains(ap);
                })
                .collect(Collectors.toList());
    }

    // ======================== 视图转换（保持字段与前端 Task 列表命名一致） ========================

    private Map<String, Object> taskView(Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("title", t.getTitle());
        m.put("description", t.getDescription());
        m.put("priority", t.getPriority() != null ? t.getPriority().name() : null);
        m.put("status", t.getStatus() != null ? t.getStatus().name() : null);
        m.put("dueDate", t.getDueDate() != null ? t.getDueDate().toString() : null);
        m.put("categoryId", t.getCategoryId());
        m.put("assigneeProfileId", t.getAssigneeProfileId());
        m.put("createdAt", t.getCreatedAt() != null ? t.getCreatedAt().toString() : null);
        m.put("source", t.getSource() != null ? t.getSource().name() : null);
        return m;
    }
}
