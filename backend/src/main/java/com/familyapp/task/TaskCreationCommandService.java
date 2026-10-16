package com.familyapp.task;

import com.familyapp.category.CategoryRepository;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.common.ResultException;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.family.FamilyRole;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.wechat.WechatNotifyService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 任务创建命令边界：用显式家庭、操作者和来源执行创建，并统一校验家庭隔离与角色权限。
 */
@Service
public class TaskCreationCommandService {

    private static final Duration DRAFT_EXPIRY = Duration.ofHours(24);

    private final TaskRepository taskRepository;
    private final CategoryRepository categoryRepository;
    private final PersonProfileRepository personProfileRepository;
    private final FamilyRepository familyRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final WechatNotifyService wechatNotifyService;

    public TaskCreationCommandService(TaskRepository taskRepository,
                                      CategoryRepository categoryRepository,
                                      PersonProfileRepository personProfileRepository,
                                      FamilyRepository familyRepository,
                                      FamilyMemberRepository familyMemberRepository,
                                      WechatNotifyService wechatNotifyService) {
        this.taskRepository = taskRepository;
        this.categoryRepository = categoryRepository;
        this.personProfileRepository = personProfileRepository;
        this.familyRepository = familyRepository;
        this.familyMemberRepository = familyMemberRepository;
        this.wechatNotifyService = wechatNotifyService;
    }

    /**
     * 使用同一显式身份创建一组任务。
     * 调用方提供同一家庭和操作者，服务按数据库中的最新成员角色执行校验。
     */
    @Transactional
    public List<Task> create(Long familyId,
                             Long actorUserId,
                             TaskSource source,
                             List<CreateTaskRequest> requests) {
        return createBatch(familyId, actorUserId, source, requests, false);
    }

    /** 微信确认批次固定以 PENDING/AGENT 创建，不接受候选覆盖业务状态或来源。 */
    @Transactional
    public List<Task> createPendingAgentBatch(Long familyId,
                                             Long actorUserId,
                                             List<CreateTaskRequest> requests) {
        return createBatch(familyId, actorUserId, TaskSource.AGENT, requests, true);
    }

    private List<Task> createBatch(Long familyId,
                                  Long actorUserId,
                                  TaskSource source,
                                  List<CreateTaskRequest> requests,
                                  boolean confirmedCandidate) {
        if (familyId == null) {
            throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED);
        }
        if (actorUserId == null) {
            throw new ApiException(ResultCode.UNAUTHORIZED);
        }
        if (source == null || requests == null || requests.isEmpty()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        familyRepository.findByIdAndDeletedAtIsNull(familyId)
                .orElseThrow(() -> new ApiException(ResultCode.ROLE_NO_PERMISSION,
                        "当前家庭不可用或您已不是家庭成员"));
        FamilyMember actor = familyMemberRepository
                .findByFamilyIdAndUserIdAndLeftAtIsNull(familyId, actorUserId)
                .orElseThrow(() -> new ApiException(ResultCode.ROLE_NO_PERMISSION,
                        "当前家庭不可用或您已不是家庭成员"));

        // 先构建并校验整批内存对象，任一字段无效时尚未执行任何 INSERT。
        List<Task> prepared = requests.stream()
                .map(request -> prepareOne(familyId, actorUserId, actor.getRole(), source,
                        request, confirmedCandidate))
                .toList();
        List<Task> saved = prepared.stream().map(taskRepository::save).toList();
        // 只持久化队列记录，加入同一事务；独立发送器提交后才能看到这些提醒。
        saved.forEach(wechatNotifyService::enqueueTaskAssigned);
        return saved;
    }

    private Task prepareOne(Long familyId,
                            Long actorUserId,
                            FamilyRole actorRole,
                            TaskSource source,
                            CreateTaskRequest input,
                            boolean confirmedCandidate) {
        validateRequestIdentity(input, familyId, actorUserId);
        // 补齐儿童执行人时不修改调用方候选快照，失败重试仍使用原始输入。
        CreateTaskRequest request = new CreateTaskRequest();
        BeanUtils.copyProperties(input, request);
        validateTitle(request.getTitle());
        prepareChildAssignment(request, familyId, actorUserId, actorRole);
        validateCategory(request.getCategoryId(), familyId);

        Task task = new Task();
        task.setFamilyId(familyId);
        task.setCreatedBy(actorUserId);
        task.setTitle(request.getTitle());
        task.setDescription(request.getDescription());
        task.setDueDate(request.getDueDate());
        task.setPriority(parsePriorityOrDefault(request.getPriority(), TaskPriority.MEDIUM));
        task.setSource(source);
        task.setCategoryId(request.getCategoryId());
        applyAssigneeTransition(task, request, familyId);

        TaskStatus status = parseStatusOrDefault(request.getStatus(), TaskStatus.PENDING);
        if (confirmedCandidate && (status != TaskStatus.PENDING
                || (request.getSource() != null && !request.getSource().isBlank()
                && !TaskSource.AGENT.name().equalsIgnoreCase(request.getSource())))) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,
                    "微信确认任务必须以待处理状态和 AI 来源创建");
        }
        if (status == TaskStatus.CANCELLED || status == TaskStatus.COMPLETED) {
            throw new ApiException(ResultCode.BAD_REQUEST_STATUS_TRANSITION,
                    "cannot create new task as " + status);
        }
        task.setStatus(status);
        if (status == TaskStatus.DRAFT) {
            task.setExpiresAt(Instant.now().plus(DRAFT_EXPIRY));
        }

        return task;
    }

    private void validateRequestIdentity(CreateTaskRequest request, Long familyId, Long actorUserId) {
        if (request == null) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        if (request.getFamilyId() != null && !request.getFamilyId().equals(familyId)) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION, "不能为其他家庭创建任务");
        }
        if (request.getCreatedBy() != null && !request.getCreatedBy().equals(actorUserId)) {
            throw new ApiException(ResultCode.ROLE_NO_PERMISSION, "不能冒用其他成员创建任务");
        }
    }

    private void validateTitle(String title) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "任务标题不能为空且不能超过 200 个字符");
        }
    }

    private void validateCategory(Long categoryId, Long familyId) {
        if (categoryId != null && categoryRepository
                .findByIdAndFamilyIdAndDeletedAtIsNull(categoryId, familyId).isEmpty()) {
            throw new ApiException(ResultCode.CATEGORY_NOT_FOUND,
                    "referenced category " + categoryId + " not found");
        }
    }

    /** CHILD 新建任务只能指派自己；未指定时自动补齐旧、新执行人字段。 */
    private void prepareChildAssignment(CreateTaskRequest request,
                                        Long familyId,
                                        Long actorUserId,
                                        FamilyRole actorRole) {
        if (actorRole != FamilyRole.CHILD) {
            return;
        }
        if (request.getAssigneeId() != null && !request.getAssigneeId().equals(actorUserId)) {
            throw childPermission();
        }
        if (request.getAssigneeProfileId() != null && !profileBelongsToUser(
                familyId, request.getAssigneeProfileId(), actorUserId)) {
            throw childPermission();
        }
        if (request.getAssigneeId() == null && request.getAssigneeProfileId() == null) {
            request.setAssigneeId(actorUserId);
            personProfileRepository
                    .findByFamilyIdAndUserIdAndDeletedAtIsNullOrderByIdAsc(familyId, actorUserId)
                    .map(PersonProfile::getId)
                    .ifPresent(request::setAssigneeProfileId);
        }
    }

    private ApiException childPermission() {
        return new ApiException(ResultCode.ROLE_NO_PERMISSION, "孩子成员只能给自己创建任务");
    }

    private boolean profileBelongsToUser(Long familyId, Long profileId, Long userId) {
        return personProfileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(familyId, profileId)
                .map(PersonProfile::getUserId)
                .filter(userId::equals)
                .isPresent();
    }

    /** 执行人双字段过渡策略，所有档案查询都限定在当前家庭。 */
    private void applyAssigneeTransition(Task task, CreateTaskRequest request, Long familyId) {
        boolean hasUser = request.getAssigneeId() != null;
        boolean hasProfile = request.getAssigneeProfileId() != null;
        PersonProfile profile = null;

        if (hasUser && familyMemberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(
                familyId, request.getAssigneeId()).isEmpty()) {
            throw new ResultException(40010, "请选择当前家庭中的有效成员");
        }
        if (hasProfile) {
            profile = personProfileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(
                    familyId, request.getAssigneeProfileId())
                    .orElseThrow(() -> new ResultException(40010, "请选择当前家庭中有效的人员档案"));
        }

        if (hasProfile && !hasUser) {
            task.setAssigneeProfileId(request.getAssigneeProfileId());
            task.setAssigneeId(null);
        } else if (hasUser && !hasProfile) {
            task.setAssigneeId(request.getAssigneeId());
            personProfileRepository
                    .findByFamilyIdAndUserIdAndDeletedAtIsNullOrderByIdAsc(familyId, request.getAssigneeId())
                    .map(PersonProfile::getId)
                    .ifPresent(task::setAssigneeProfileId);
        } else if (hasUser) {
            Long profileUserId = profile != null ? profile.getUserId() : null;
            if (profileUserId == null || !profileUserId.equals(request.getAssigneeId())) {
                throw new ResultException(40010,
                        "指派给谁参数冲突，请仅使用新档案字段（assigneeProfileId）");
            }
            task.setAssigneeProfileId(request.getAssigneeProfileId());
            task.setAssigneeId(request.getAssigneeId());
        }
    }

    private TaskStatus parseStatusOrDefault(String raw, TaskStatus defaultStatus) {
        if (raw == null || raw.isBlank()) {
            return defaultStatus;
        }
        try {
            return TaskStatus.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_STATUS_TRANSITION,
                    "invalid status value, must be one of " + Arrays.toString(TaskStatus.values()));
        }
    }

    private TaskPriority parsePriorityOrDefault(String raw, TaskPriority defaultPriority) {
        if (raw == null || raw.isBlank()) {
            return defaultPriority;
        }
        try {
            return TaskPriority.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_INVALID_PRIORITY,
                    "invalid priority, must be one of " + Arrays.toString(TaskPriority.values()));
        }
    }
}
