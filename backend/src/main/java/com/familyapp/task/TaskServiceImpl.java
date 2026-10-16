package com.familyapp.task;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.common.ResultException;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.category.CategoryRepository;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskFilter;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.UpdateTaskRequest;
import com.familyapp.wechat.WechatNotifyService;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class TaskServiceImpl implements TaskService {

    private final TaskRepository repo;
    private final CategoryRepository categoryRepo;
    private final PersonProfileRepository personProfileRepository;
    private final com.familyapp.agent.TaskEventService taskEventService;
    private final WechatNotifyService wechatNotifyService;
    private final TaskCreationCommandService taskCreationCommandService;

    private static final EnumMap<TaskStatus, EnumSet<TaskStatus>> TRANSITIONS =
        new EnumMap<>(TaskStatus.class);

    static {
        TRANSITIONS.put(TaskStatus.DRAFT, EnumSet.of(
            TaskStatus.DRAFT, TaskStatus.PENDING, TaskStatus.CANCELLED
        ));
        TRANSITIONS.put(TaskStatus.PENDING, EnumSet.of(
            TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED, TaskStatus.CANCELLED
        ));
        TRANSITIONS.put(TaskStatus.IN_PROGRESS, EnumSet.of(
            TaskStatus.PENDING, TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED, TaskStatus.CANCELLED
        ));
        TRANSITIONS.put(TaskStatus.COMPLETED, EnumSet.of(TaskStatus.COMPLETED));
        TRANSITIONS.put(TaskStatus.CANCELLED, EnumSet.of(TaskStatus.CANCELLED));
    }

    public TaskServiceImpl(TaskRepository repo,
                           CategoryRepository categoryRepo,
                           PersonProfileRepository personProfileRepository,
                           com.familyapp.agent.TaskEventService taskEventService,
                           WechatNotifyService wechatNotifyService,
                           TaskCreationCommandService taskCreationCommandService) {
        this.repo = repo;
        this.categoryRepo = categoryRepo;
        this.personProfileRepository = personProfileRepository;
        this.taskEventService = taskEventService;
        this.wechatNotifyService = wechatNotifyService;
        this.taskCreationCommandService = taskCreationCommandService;
    }

    @Override
    public TaskResponse create(CreateTaskRequest req) {
        return TaskResponse.fromEntity(createTask(req));
    }

    /**
     * 底层实现：返回 Task 实体（供 create() DTO 转换 + 单元测试/InternalAgent 直接访问原始列）。
     *
     * <p>Change 8 过渡策略 3 分支（spec §1.3 + plan §B 核心决策 2）：
     * <ol>
     *   <li>仅传 assigneeProfileId → 直接写新列，assigneeId 留空</li>
     *   <li>仅传 assigneeId → 后端按 family+user 反查 person_profile，回填 assigneeProfileId（找不到则只写旧列，不阻断）</li>
     *   <li>两列都传且不一致 → ResultException(40010, "指派给谁参数冲突，请仅使用新档案字段")</li>
     * </ol>
     */
    @Override
    public Task createTask(CreateTaskRequest req) {
        Long familyId = resolveFamilyId(req);
        Long createdBy = resolveCreatedBy(req);
        TaskSource source = parseSourceOrDefault(req.getSource(), TaskSource.MANUAL);
        return taskCreationCommandService.create(
                familyId, createdBy, source, List.of(req)).get(0);
    }

    /**
     * assignee 双字段过渡 3 分支（Change 8）。
     * 结果直接写入 t.assigneeId / t.assigneeProfileId。
     */
    private void applyAssigneeTransition(Task t, CreateTaskRequest req, Long familyId) {
        boolean hasOld = req.getAssigneeId() != null;
        boolean hasNew = req.getAssigneeProfileId() != null;
        if (hasNew && personProfileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(
                familyId, req.getAssigneeProfileId()).isEmpty()) {
            throw new ResultException(40010, "请选择当前家庭中有效的人员档案");
        }

        if (hasNew && !hasOld) {
            // 分支 1：仅传 assigneeProfileId → 直接写新列，assigneeId 留空
            t.setAssigneeProfileId(req.getAssigneeProfileId());
            t.setAssigneeId(null);
        } else if (hasOld && !hasNew) {
            // 分支 2：仅传 assigneeId → 反查 family+user 的 person_profile 回填新列
            t.setAssigneeId(req.getAssigneeId());
            personProfileRepository
                .findByFamilyIdAndUserIdAndDeletedAtIsNullOrderByIdAsc(familyId, req.getAssigneeId())
                .map(PersonProfile::getId)
                .ifPresent(t::setAssigneeProfileId);
        } else if (hasOld && hasNew) {
            // 分支 3：两列都传 → 校验一致性；不同人抛 40010
            Long newProfileId = req.getAssigneeProfileId();
            Long oldUserId = req.getAssigneeId();
            // 查 new 列对应 profile 的 user_id 看是否与 assigneeId 相同
            // （不同 profile 时 user_id 可能为空=宝宝/宠物，或不同用户 → 判定不一致）
            PersonProfile pp = personProfileRepository
                    .findByFamilyIdAndIdAndDeletedAtIsNull(familyId, newProfileId)
                    .orElse(null);
            Long profileUserId = pp != null ? pp.getUserId() : null;
            if (profileUserId == null || !profileUserId.equals(oldUserId)) {
                throw new ResultException(40010,
                        "指派给谁参数冲突，请仅使用新档案字段（assigneeProfileId）");
            }
            t.setAssigneeProfileId(newProfileId);
            t.setAssigneeId(oldUserId);
        }
        // else：两列都 null → 未指派，两列都留空
    }

    /** 登录请求只信任上下文；无上下文的内部调用必须显式给出 family_id。 */
    private Long resolveFamilyId(CreateTaskRequest req) {
        UserContext.CurrentUser current = currentOrNull();
        if (current != null) {
            if (current.familyId() == null) {
                throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED);
            }
            if (req.getFamilyId() != null && !req.getFamilyId().equals(current.familyId())) {
                throw new ApiException(ResultCode.ROLE_NO_PERMISSION, "不能为其他家庭创建任务");
            }
            return current.familyId();
        }
        if (req.getFamilyId() == null) {
            throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED);
        }
        return req.getFamilyId();
    }

    /** 登录请求只信任当前用户；无上下文的内部调用必须显式给出 created_by。 */
    private Long resolveCreatedBy(CreateTaskRequest req) {
        UserContext.CurrentUser current = currentOrNull();
        if (current != null) {
            if (req.getCreatedBy() != null && !req.getCreatedBy().equals(current.userId())) {
                throw new ApiException(ResultCode.ROLE_NO_PERMISSION, "不能冒用其他成员创建任务");
            }
            return current.userId();
        }
        if (req.getCreatedBy() == null) {
            throw new ApiException(ResultCode.UNAUTHORIZED);
        }
        return req.getCreatedBy();
    }

    private UserContext.CurrentUser currentOrNull() {
        try {
            return UserContext.current();
        } catch (ApiException e) {
            if (e.getCode() == ResultCode.UNAUTHORIZED) {
                return null;
            }
            throw e;
        }
    }

    private boolean profileBelongsToUser(Long familyId, Long profileId, Long userId) {
        return personProfileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(familyId, profileId)
                .map(PersonProfile::getUserId)
                .filter(userId::equals)
                .isPresent();
    }

    private ApiException childPermission(String message) {
        return new ApiException(ResultCode.ROLE_NO_PERMISSION, message);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaskResponse> list(TaskFilter filter) {
        return repo.findAll(specification(filter), Sort.by(Sort.Direction.DESC, "createdAt")).stream()
            .map(TaskResponse::fromEntity).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<TaskResponse> page(TaskFilter filter, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "页码不能小于零，每页需要 1 至 100 条");
        }
        var pageable = org.springframework.data.domain.PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return repo.findAll(specification(filter), pageable).map(TaskResponse::fromEntity);
    }

    private Specification<Task> specification(TaskFilter filter) {
        Long familyId = UserContext.current().familyId();
        if (familyId == null) throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED);
        Specification<Task> spec = Specification.where(TaskSpecifications.notDeleted())
            .and(TaskSpecifications.withValidDraftPredicate())
            .and((root, q, cb) -> cb.equal(root.get("familyId"), familyId));
        if (filter.status() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), filter.status()));
        }
        if (filter.categoryId() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("categoryId"), filter.categoryId()));
        }
        if (filter.source() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("source"), filter.source()));
        }
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            String kw = "%" + filter.keyword().toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), kw),
                cb.like(cb.lower(root.get("description")), kw)
            ));
        }
        if (filter.dueBefore() != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("dueDate"), filter.dueBefore()));
        }
        // 新增维度：assigneeId（按执行人筛选，"我的任务"用）
        if (filter.assigneeId() != null) {
            spec = spec.and(TaskAssignees.forUser(familyId, filter.assigneeId()));
        }
        // 新增维度：overdue（true 时只看逾期未完成，dueDate 早于 today 且状态不在 COMPLETED/CANCELLED）
        if (Boolean.TRUE.equals(filter.overdue())) {
            LocalDate today = filter.today() != null ? filter.today() : LocalDate.now();
            spec = spec.and((root, q, cb) -> cb.isNotNull(root.get("dueDate")));
            spec = spec.and((root, q, cb) -> cb.lessThan(root.get("dueDate"), today));
            spec = spec.and((root, q, cb) -> root.get("status").in(
                java.util.Arrays.asList(TaskStatus.COMPLETED, TaskStatus.CANCELLED)).not());
        }
        return spec;
    }

    @Override
    @Transactional(readOnly = true)
    public com.familyapp.task.dto.TaskStats stats() {
        UserContext.CurrentUser user = UserContext.current();
        Long familyId = user.familyId();
        Long userId = user.userId();
        LocalDate today = LocalDate.now();
        long familyPending = repo.count(specification(new TaskFilter(TaskStatus.PENDING, null, null, null, null)));
        long minePending = repo.count(specification(new TaskFilter(TaskStatus.PENDING, null, null, null, null,
                userId, false, today)));
        long familyOverdue = repo.count(specification(new TaskFilter(null, null, null, null, null,
                null, true, today)));
        long mineOverdue = repo.count(specification(new TaskFilter(null, null, null, null, null,
                userId, true, today)));
        return new com.familyapp.task.dto.TaskStats(familyPending, minePending, familyOverdue, mineOverdue);
    }

    @Override
    @Transactional(readOnly = true)
    public TaskResponse get(Long id) {
        Task t = repo.findByIdAndFamilyId(id, UserContext.current().familyId())
            .orElseThrow(() -> new ApiException(ResultCode.TASK_NOT_FOUND));
        return TaskResponse.fromEntity(t);
    }

    @Override
    public TaskResponse update(Long id, UpdateTaskRequest req) {
        UserContext.CurrentUser current = UserContext.current();
        Task t = repo.findByIdAndFamilyId(id, current.familyId())
            .orElseThrow(() -> new ApiException(ResultCode.TASK_NOT_FOUND));
        checkChildUpdateAccess(current, t, req);
        if (req.getTitle() != null) t.setTitle(req.getTitle());
        if (req.getDescription() != null) t.setDescription(req.getDescription());
        if (req.getDueDate() != null) t.setDueDate(req.getDueDate());
        if (req.getPriority() != null) t.setPriority(parsePriority(req.getPriority()));
        if (req.getSource() != null) t.setSource(parseSource(req.getSource()));
        if (req.getCategoryId() != null) {
            validateCategoryExists(req.getCategoryId());
            t.setCategoryId(req.getCategoryId());
        }
        Long oldProfile = t.getAssigneeProfileId();
        Long oldUser = t.getAssigneeId();
        if (req.isAssigneeProfileProvided() || req.getAssigneeId() != null) {
            CreateTaskRequest assignment = new CreateTaskRequest();
            assignment.setAssigneeProfileId(req.getAssigneeProfileId());
            assignment.setAssigneeId(req.getAssigneeId());
            t.setAssigneeProfileId(null);
            t.setAssigneeId(null);
            applyAssigneeTransition(t, assignment, t.getFamilyId());
        }
        boolean completedEdge = false;
        boolean acceptedDraft = false;
        if (req.getStatus() != null) {
            TaskStatus target = parseStatus(req.getStatus());
            TaskStatus before = t.getStatus();
            acceptedDraft = before == TaskStatus.DRAFT && target == TaskStatus.PENDING;
            validateTransition(before, target);
            t.setStatus(target);
            if (t.getStatus() == TaskStatus.PENDING && t.getExpiresAt() != null) {
                t.setExpiresAt(null);
            }
            completedEdge = TaskStatus.COMPLETED.equals(target)
                    && !TaskStatus.COMPLETED.equals(before);
        }
        Task saved = repo.save(t);
        if (acceptedDraft || !java.util.Objects.equals(oldProfile, saved.getAssigneeProfileId())
                || !java.util.Objects.equals(oldUser, saved.getAssigneeId())) {
            wechatNotifyService.enqueueTaskAssigned(saved);
        }
        if (completedEdge) {
            // 边沿触发：完成事件在事务末挂接，依赖触发链与状态更新同事务回滚
            taskEventService.onCompleted(saved.getId(), saved.getFamilyId());
        }
        return TaskResponse.fromEntity(saved);
    }

    @Override
    public void delete(Long id) {
        UserContext.CurrentUser current = UserContext.current();
        if ("CHILD".equals(current.role())) {
            throw childPermission("孩子角色不能删除任务，请让家长帮忙操作");
        }
        Task t = repo.findByIdAndFamilyId(id, current.familyId())
            .orElseThrow(() -> new ApiException(ResultCode.TASK_NOT_FOUND));
        if (t.getDeletedAt() != null) {
            throw new ApiException(ResultCode.TASK_NOT_FOUND);
        }
        t.setDeletedAt(Instant.now());
        repo.save(t);
    }

    /** CHILD 只能修改自己创建或分配给自己的任务，且不能改派给其他成员。 */
    private void checkChildUpdateAccess(UserContext.CurrentUser current, Task task, UpdateTaskRequest req) {
        if (!"CHILD".equals(current.role())) {
            return;
        }
        Long userId = current.userId();
        boolean related = userId.equals(task.getCreatedBy())
                || userId.equals(task.getAssigneeId())
                || (task.getAssigneeProfileId() != null && profileBelongsToUser(
                        current.familyId(), task.getAssigneeProfileId(), userId));
        if (!related) {
            throw childPermission("孩子成员只能修改自己创建或分配给自己的任务");
        }
        if (req.getAssigneeId() != null && !req.getAssigneeId().equals(userId)) {
            throw childPermission("孩子成员不能把任务改派给其他成员");
        }
        if (req.getAssigneeProfileId() != null && !profileBelongsToUser(
                current.familyId(), req.getAssigneeProfileId(), userId)) {
            throw childPermission("孩子成员不能把任务改派给其他成员");
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void expireDrafts() {
        List<Task> expired = repo.findByStatusAndExpiresAtBefore(
            TaskStatus.DRAFT, Instant.now()
        );
        for (Task t : expired) {
            t.setStatus(TaskStatus.CANCELLED);
        }
        repo.saveAll(expired);
    }

    private TaskStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ResultCode.BAD_REQUEST_STATUS_TRANSITION, "status is blank");
        }
        try {
            return TaskStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_STATUS_TRANSITION,
                "invalid status value, must be one of " + Arrays.toString(TaskStatus.values()));
        }
    }

    private TaskPriority parsePriority(String raw) {
        try {
            return TaskPriority.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_INVALID_PRIORITY,
                "invalid priority, must be one of " + Arrays.toString(TaskPriority.values()));
        }
    }

    private TaskSource parseSource(String raw) {
        try {
            return TaskSource.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_INVALID_SOURCE,
                "invalid source, must be one of " + Arrays.toString(TaskSource.values()));
        }
    }

    private TaskSource parseSourceOrDefault(String raw, TaskSource def) {
        if (raw == null || raw.isBlank()) return def;
        return parseSource(raw);
    }

    private void validateCategoryExists(Long categoryId) {
        if (categoryRepo.findByIdAndFamilyIdAndDeletedAtIsNull(
                categoryId, UserContext.current().familyId()).isEmpty()) {
            throw new ApiException(ResultCode.CATEGORY_NOT_FOUND,
                "referenced category " + categoryId + " not found");
        }
    }
    private void validateTransition(TaskStatus current, TaskStatus target) {
        EnumSet<TaskStatus> allowed = TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new ApiException(ResultCode.BAD_REQUEST_STATUS_TRANSITION,
                "invalid status transition from " + current + " to " + target);
        }
    }
}