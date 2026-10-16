package com.familyapp.agent;

import com.familyapp.agent.AgentApiDtos.BatchSubtaskItem;
import com.familyapp.agent.AgentApiDtos.BatchSubtaskReq;
import com.familyapp.agent.AgentApiDtos.BatchSubtaskResp;
import com.familyapp.agent.AgentApiDtos.CategoryResp;
import com.familyapp.agent.AgentApiDtos.FewShotResp;
import com.familyapp.agent.AgentApiDtos.NoteCreateReq;
import com.familyapp.agent.AgentApiDtos.NoteResp;
import com.familyapp.agent.AgentApiDtos.NewVersionReq;
import com.familyapp.agent.AgentApiDtos.OnCompletedReq;
import com.familyapp.agent.AgentApiDtos.SubtaskResp;
import com.familyapp.category.Category;
import com.familyapp.category.CategoryRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.common.ApiException;
import com.familyapp.common.Result;
import com.familyapp.common.ResultCode;
import com.familyapp.common.ResultException;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.dto.PersonProfileResponse;
import com.familyapp.task.Task;
import com.familyapp.task.TaskPriority;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskSource;
import com.familyapp.task.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * C 组 18 内部 Agent 端点（规格 §3.4）：前缀 /api/internal/agent/**，
 * 由 InternalSecretInterceptor 校验 X-Internal-Secret。
 * 家庭作用域一律来自显式 family_id 参数（机器调用无用户上下文），
 * 所有跨家庭资源访问统一按对应 NOT_FOUND 错误码拒绝（防枚举探测）。
 */
@RestController
@RequestMapping("/api/internal/agent")
public class InternalAgentController {

    private static final Logger log = LoggerFactory.getLogger(InternalAgentController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskRepository taskRepository;
    private final SubtaskRepository subtaskRepository;
    private final NoteRepository noteRepository;
    private final CategoryRepository categoryRepository;
    private final RuleTemplateRepository templateRepository;
    private final SummaryExecutionLogRepository summaryLogRepository;
    private final RoutineMatcherService routineMatcherService;
    private final PendingScheduleService pendingScheduleService;
    private final TaskEventService taskEventService;
    private final TemplateVersionService templateVersionService;
    private final SummaryLockService summaryLockService;
    private final FamilyRepository familyRepository;
    private final PersonProfileRepository personProfileRepository;
    private final DecomposeSessionRepository decomposeSessionRepository;
    private final com.familyapp.wechat.WechatNotifyService wechatNotifyService;

    public InternalAgentController(TaskRepository taskRepository,
                                   SubtaskRepository subtaskRepository,
                                   NoteRepository noteRepository,
                                   CategoryRepository categoryRepository,
                                   RuleTemplateRepository templateRepository,
                                   SummaryExecutionLogRepository summaryLogRepository,
                                   RoutineMatcherService routineMatcherService,
                                   PendingScheduleService pendingScheduleService,
                                   TaskEventService taskEventService,
                                   TemplateVersionService templateVersionService,
                                   SummaryLockService summaryLockService,
                                   FamilyRepository familyRepository,
                                   PersonProfileRepository personProfileRepository,
                                   DecomposeSessionRepository decomposeSessionRepository,
                                   com.familyapp.wechat.WechatNotifyService wechatNotifyService) {
        this.taskRepository = taskRepository;
        this.subtaskRepository = subtaskRepository;
        this.noteRepository = noteRepository;
        this.categoryRepository = categoryRepository;
        this.templateRepository = templateRepository;
        this.summaryLogRepository = summaryLogRepository;
        this.routineMatcherService = routineMatcherService;
        this.pendingScheduleService = pendingScheduleService;
        this.taskEventService = taskEventService;
        this.templateVersionService = templateVersionService;
        this.summaryLockService = summaryLockService;
        this.familyRepository = familyRepository;
        this.personProfileRepository = personProfileRepository;
        this.decomposeSessionRepository = decomposeSessionRepository;
        this.wechatNotifyService = wechatNotifyService;
    }

    // ==================== C-1 ~ C-9 任务与子待办 ====================

    /** C-1 get_tasks：过滤列表（内存分页）。 */
    @GetMapping("/tasks")
    public Result<List<AgentApiDtos.TaskResp>> getTasks(
            @RequestParam("family_id") Long familyId,
            @RequestParam(required = false) String status,
            @RequestParam(name = "category_id", required = false) Long categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(name = "assignee_id", required = false) Long assigneeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<Task> filtered = filteredTasks(familyId, status, categoryId, keyword, assigneeId);
        int limit = Math.max(1, Math.min(size, 200));
        int from = Math.max(0, page * limit);
        int to = Math.min(filtered.size(), from + limit);
        List<AgentApiDtos.TaskResp> items = from >= to
                ? List.of()
                : filtered.subList(from, to).stream().map(t -> toTaskResp(t, false)).toList();
        return Result.ok(items);
    }

    /** C-2 get_task_detail：任务 + 子待办 + 双层便签嵌套。 */
    @GetMapping("/tasks/{id}")
    public Result<AgentApiDtos.TaskResp> getTaskDetail(
            @PathVariable Long id,
            @RequestParam("family_id") Long familyId) {
        Task task = requireTask(id, familyId);
        return Result.ok(toTaskResp(task, true));
    }

    /** C-3 create_task：source 强制 agent；分类必须属于同家庭。 */
    @PostMapping("/tasks")
    @Transactional
    public Result<AgentApiDtos.TaskResp> createTask(
            @RequestBody AgentApiDtos.TaskCreateReq req,
            @RequestParam("family_id") Long familyId) {
        if (req.title() == null || req.title().isBlank()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        validateCategory(req.categoryId(), familyId);
        Task task = new Task();
        task.setFamilyId(familyId);
        task.setTitle(req.title());
        task.setDescription(req.description());
        if (req.priority() != null) {
            task.setPriority(parseEnum(req.priority(), TaskPriority.class, "priority"));
        }
        task.setCategoryId(req.categoryId());
        task.setAssigneeId(req.assigneeId());
        task.setExpiresAt(req.expiresAt());
        task.setStatus(TaskStatus.PENDING);
        task.setSource(TaskSource.AGENT);
        Task saved = taskRepository.save(task);
        // Change 12：AI 拆解落库任务若指派人有微信绑定，发模板消息（服务内部吞异常）
        wechatNotifyService.notifyTaskAssigned(saved);
        return Result.ok(toTaskResp(saved, true));
    }

    /** C-4 create_subtasks batch。 */
    @PostMapping("/subtasks/batch")
    @Transactional
    public Result<BatchSubtaskResp> batchCreateSubtasks(
            @RequestBody BatchSubtaskReq req,
            @RequestParam("family_id") Long familyId) {
        Task parent = requireTask(req.taskId(), familyId);
        List<BatchSubtaskItem> items = req.subtasks();
        if (items == null || items.isEmpty()) {
            throw new ApiException(ResultCode.SUBTASK_BATCH_EMPTY);
        }
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            BatchSubtaskItem item = items.get(i);
            Subtask subtask = new Subtask();
            subtask.setFamilyId(familyId);
            subtask.setParentTaskId(parent.getId());
            subtask.setTitle(item.title());
            subtask.setDescription(item.description());
            subtask.setSortOrder(item.sortOrder() != null ? item.sortOrder() : i + 1);
            subtask.setStatus(TaskStatus.PENDING);
            subtask.setIsAiGenerated(true);
            ids.add(subtaskRepository.save(subtask).getId());
        }
        return Result.ok(new BatchSubtaskResp(ids.size(), ids));
    }

    /** C-5 update_task：仅非空字段生效；状态走 5 值枚举校验。 */
    @PutMapping("/tasks/{id}")
    @Transactional
    public Result<AgentApiDtos.TaskResp> updateTask(
            @PathVariable Long id,
            @RequestBody AgentApiDtos.TaskUpdateReq req,
            @RequestParam("family_id") Long familyId) {
        Task task = requireTask(id, familyId);
        if (req.title() != null) {
            if (req.title().isBlank()) {
                throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
            }
            task.setTitle(req.title());
        }
        if (req.description() != null) {
            task.setDescription(req.description());
        }
        if (req.priority() != null) {
            task.setPriority(parseEnum(req.priority(), TaskPriority.class, "priority"));
        }
        if (req.status() != null) {
            task.setStatus(parseEnum(req.status(), TaskStatus.class, "status"));
        }
        if (req.categoryId() != null) {
            validateCategory(req.categoryId(), familyId);
            task.setCategoryId(req.categoryId());
        }
        if (req.dueDate() != null) {
            task.setDueDate(req.dueDate());
        }
        return Result.ok(toTaskResp(task, true));
    }

    /** C-6 update_subtask。 */
    @PutMapping("/subtasks/{id}")
    @Transactional
    public Result<AgentApiDtos.SubtaskResp> updateSubtask(
            @PathVariable Long id,
            @RequestBody AgentApiDtos.SubtaskUpdateReq req,
            @RequestParam("family_id") Long familyId) {
        Subtask subtask = requireSubtask(id, familyId);
        if (req.status() != null) {
            subtask.setStatus(parseEnum(req.status(), TaskStatus.class, "status"));
        }
        if (req.title() != null && !req.title().isBlank()) {
            subtask.setTitle(req.title());
        }
        if (req.description() != null) {
            subtask.setDescription(req.description());
        }
        if (req.sortOrder() != null) {
            subtask.setSortOrder(req.sortOrder());
        }
        return Result.ok(toSubtaskResp(subtask));
    }

    /** C-7 delete_subtask：物理删除子待办行。 */
    @DeleteMapping("/subtasks/{id}")
    @Transactional
    public Result<Void> deleteSubtask(@PathVariable Long id,
                                      @RequestParam("family_id") Long familyId) {
        subtaskRepository.delete(requireSubtask(id, familyId));
        return Result.ok();
    }

    /** C-8 add_note。 */
    @PostMapping("/notes")
    @Transactional
    public Result<NoteResp> addNote(@RequestBody NoteCreateReq req,
                                    @RequestParam("family_id") Long familyId) {
        if (!"task".equals(req.targetType()) && !"subtask".equals(req.targetType())) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        if ("task".equals(req.targetType())) {
            requireTask(req.targetId(), familyId);
        } else {
            requireSubtask(req.targetId(), familyId);
        }
        if (req.content() == null || req.content().isBlank()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        Note note = new Note();
        note.setFamilyId(familyId);
        note.setTargetType(req.targetType());
        note.setTargetId(req.targetId());
        note.setContent(req.content());
        return Result.ok(toNoteResp(noteRepository.save(note)));
    }

    /** C-9 delete_note。 */
    @DeleteMapping("/notes/{id}")
    @Transactional
    public Result<Void> deleteNote(@PathVariable Long id,
                                   @RequestParam("family_id") Long familyId) {
        Note note = noteRepository.findById(id)
                .filter(n -> n.getFamilyId().equals(familyId))
                .orElseThrow(() -> new ApiException(ResultCode.NOTE_NOT_FOUND));
        noteRepository.delete(note);
        return Result.ok();
    }

    // ==================== C-10 ~ C-14 查询与日志 ====================

    /** C-10 get_categories。 */
    @GetMapping("/categories")
    public Result<List<CategoryResp>> getCategories(@RequestParam("family_id") Long familyId) {
        return Result.ok(categoryRepository
                .findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(familyId)
                .stream()
                .map(c -> new CategoryResp(c.getId(), c.getName(), c.getIcon(), c.getColor(),
                        c.getSortOrder()))
                .toList());
    }

    /** C-11 get_rule_templates：type/is_active 可选过滤。 */
    @GetMapping("/templates")
    public Result<List<AgentApiDtos.TemplateResp>> getTemplates(
            @RequestParam("family_id") Long familyId,
            @RequestParam(required = false) String type,
            @RequestParam(name = "is_active", required = false) Boolean isActive) {
        List<AgentApiDtos.TemplateResp> items = templateRepository
                .findByFamilyIdOrderByIdAsc(familyId)
                .stream()
                .filter(t -> type == null || type.equals(t.getType()))
                .filter(t -> isActive == null || isActive.equals(t.getIsActive()))
                .map(this::toTemplateResp)
                .toList();
        return Result.ok(items);
    }

    /** C-12 get_completed_few_shot：最近完成的带子待办样本（默认 10 条）。 */
    @GetMapping("/templates/few-shot")
    public Result<FewShotResp> getFewShot(
            @RequestParam("family_id") Long familyId,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        List<AgentApiDtos.TaskWithSubtasks> samples = taskRepository
                .findWithFilters(familyId, TaskStatus.COMPLETED, null, null, null, null)
                .stream()
                .limit(Math.max(1, Math.min(limit, 50)))
                .map(t -> new AgentApiDtos.TaskWithSubtasks(t.getId(), t.getTitle(),
                        t.getDescription(),
                        t.getPriority() == null ? null : t.getPriority().name(),
                        t.getStatus() == null ? null : t.getStatus().name(),
                        toSubtaskRespList(t.getId())))
                .toList();
        return Result.ok(new FewShotResp(samples));
    }

    /** C-13 get_workload：pending/completed(窗口内)/high_priority 计数。 */
    @GetMapping("/workload")
    public Result<AgentApiDtos.WorkloadResp> getWorkload(
            @RequestParam("family_id") Long familyId,
            @RequestParam(name = "assignee_id", required = false) Long assigneeId,
            @RequestParam(name = "window_days", defaultValue = "7") int windowDays) {
        List<Task> all = filteredTasks(familyId, null, null, null, assigneeId);
        int pending = (int) all.stream().filter(t -> t.getStatus() == TaskStatus.PENDING).count();
        Instant cutoff = Instant.now().minus(Math.max(1, windowDays), java.time.temporal.ChronoUnit.DAYS);
        int completed = (int) all.stream()
                .filter(t -> t.getStatus() == TaskStatus.COMPLETED)
                .filter(t -> t.getUpdatedAt() != null && !t.getUpdatedAt().isBefore(cutoff))
                .count();
        int highPriority = (int) all.stream()
                .filter(t -> t.getStatus() == TaskStatus.PENDING)
                .filter(t -> t.getPriority() == TaskPriority.HIGH)
                .count();
        return Result.ok(new AgentApiDtos.WorkloadResp(pending, completed, highPriority));
    }

    /** C-14 log_summary：写入 summary_execution_log 并返回 id。 */
    @PostMapping("/summary-log")
    @Transactional
    public Result<AgentApiDtos.SummaryLogResp> logSummary(
            @RequestBody AgentApiDtos.SummaryLogReq req,
            @RequestParam("family_id") Long familyId) {
        if (!"success".equals(req.status()) && !"failed".equals(req.status())) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        SummaryExecutionLog row = new SummaryExecutionLog();
        row.setFamilyId(familyId);
        row.setStatus(req.status());
        row.setCategoriesUpdated(MAPPER.valueToTree(
                req.categoriesUpdated() == null ? List.of() : req.categoriesUpdated()));
        if (req.failedCategories() != null && !req.failedCategories().isEmpty()) {
            StringBuilder reasons = new StringBuilder();
            for (var f : req.failedCategories()) {
                if (reasons.length() > 0) {
                    reasons.append("; ");
                }
                reasons.append("分类 ").append(f.categoryId()).append("：")
                        .append(f.reason() == null ? "未知原因" : f.reason());
            }
            row.setErrorMessage(reasons.toString());
        }
        row.setTriggerSource(req.triggerSource() == null ? "auto" : req.triggerSource());
        SummaryExecutionLog saved = summaryLogRepository.save(row);
        return Result.ok(new AgentApiDtos.SummaryLogResp(saved.getId(), saved.getRunAt()));
    }

    // ==================== C-15 ~ C-18 自动化 ====================

    /** C-15 routines/due-today：今日命中的日常模板列表。 */
    @GetMapping("/routines/due-today")
    public Result<List<DueTodayRoutine>> dueTodayRoutines(
            @RequestParam("family_id") Long familyId) {
        return Result.ok(routineMatcherService.dueToday(familyId));
    }

    /** C-16 schedules/pending：原子消化一条今日到期调度。 */
    @GetMapping("/schedules/pending")
    public Result<AgentApiDtos.ScheduleResp> claimPendingSchedule(
            @RequestParam("family_id") Long familyId) {
        return Result.ok(pendingScheduleService.claimNext(familyId).map(this::toScheduleResp)
                .orElse(null));
    }

    /** C-17 task-event/on-completed：完成事件触发下游产出。 */
    @PostMapping("/task-event/on-completed")
    @Transactional
    public Result<AgentApiDtos.TriggeredResp> onCompleted(
            @RequestBody OnCompletedReq req,
            @RequestParam("family_id") Long familyId) {
        if (req.sourceTaskId() == null) {
            throw new ApiException(ResultCode.TASK_NOT_FOUND);
        }
        requireTask(req.sourceTaskId(), familyId);
        int triggered = taskEventService.onCompleted(req.sourceTaskId(), familyId);
        return Result.ok(new AgentApiDtos.TriggeredResp(triggered));
    }

    /** C-18 templates/new-version：事务内提交模板新版本。 */
    @PostMapping("/templates/{id}/new-version")
    @Transactional
    public Result<AgentApiDtos.VersionResp> newVersion(
            @PathVariable Long id,
            @RequestBody NewVersionReq req,
            @RequestParam("family_id") Long familyId) {
        if (req.content() == null || req.content().isNull()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        int version = templateVersionService.createNewVersion(id, familyId, req.content(),
                req.changeSummary(), Boolean.TRUE.equals(req.isImportant()));
        return Result.ok(new AgentApiDtos.VersionResp(version));
    }

    // ==================== C-19 ~ C-22 总结锁与学习（Change 5） ====================

    /** C-19 summary-log/claim：INSERT 'running' 抢锁；唯一冲突 → acquired=false skip。 */
    @PostMapping("/summary-log/claim")
    public Result<AgentApiDtos.SummaryClaimResp> claimSummaryLog(
            @RequestBody(required = false) AgentApiDtos.SummaryClaimReq req,
            @RequestParam("family_id") Long familyId,
            @RequestParam(name = "force", defaultValue = "false") boolean force) {
        String triggerSource = req == null ? "auto" : req.triggerSource();
        if (triggerSource != null && !"auto".equals(triggerSource)
                && !"manual".equals(triggerSource)) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        try {
            SummaryExecutionLog row = summaryLockService.claim(familyId, triggerSource, force);
            return Result.ok(new AgentApiDtos.SummaryClaimResp(true, row.getId()));
        } catch (DataIntegrityViolationException e) {
            SummaryExecutionLog existing = summaryLockService.findSameDay(familyId);
            log.info("summary lock conflict family={} date=today(UTC), skip duplicate", familyId);
            return Result.ok(new AgentApiDtos.SummaryClaimResp(false,
                    existing == null ? null : existing.getId()));
        }
    }

    /** C-20 summary-log/{id}：收尾 running → success/failed。success 时推送微信总结完成通知（Change 12）。 */
    @PutMapping("/summary-log/{id}")
    public Result<Void> finishSummaryLog(
            @PathVariable Long id,
            @RequestBody AgentApiDtos.SummaryLogFinishReq req,
            @RequestParam("family_id") Long familyId) {
        summaryLockService.finish(familyId, id, req);
        if ("success".equals(req.status())) {
            // 推送旁路：服务内部吞异常，绝不影响收尾响应
            wechatNotifyService.notifySummaryReady(familyId);
        }
        return Result.ok();
    }

    /** C-21 templates/{id}/change-summary：❌2 中文警告追加到最新版。 */
    @PostMapping("/templates/{id}/change-summary")
    public Result<AgentApiDtos.VersionResp> appendChangeSummary(
            @PathVariable Long id,
            @RequestBody AgentApiDtos.ChangeSummaryAppendReq req,
            @RequestParam("family_id") Long familyId) {
        if (req.append() == null || req.append().isBlank()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
        int version = templateVersionService.appendChangeSummary(id, familyId, req.append());
        return Result.ok(new AgentApiDtos.VersionResp(version));
    }

    /** C-22 template-versions/cleanup：版本保留策略（重要永久/普通30天/每模板最少3）。 */
    @PostMapping("/template-versions/cleanup")
    public Result<AgentApiDtos.CleanupResp> cleanupVersions(
            @RequestParam("family_id") Long familyId) {
        int deleted = templateVersionService.cleanupExpiredVersions(familyId);
        return Result.ok(new AgentApiDtos.CleanupResp(deleted));
    }

    /** C-23 active-families：22:00 总结批源（agent_reader 无 families.deleted_at 权限，故走后端）。 */
    @GetMapping("/active-families")
    public Result<List<Long>> activeFamilies() {
        return Result.ok(familyRepository.findAllActiveIds());
    }

    /** C-24 templates/{id}/versions：diff 基线与 ❌2 幂等判定的版本历史读取。 */
    @GetMapping("/templates/{id}/versions")
    public Result<AgentApiDtos.TemplateVersionsResp> templateVersions(
            @PathVariable Long id,
            @RequestParam("family_id") Long familyId,
            @RequestParam(name = "limit", defaultValue = "10") int limit) {
        List<AgentApiDtos.TemplateVersionItem> items = templateVersionService
                .recentVersions(id, familyId, limit)
                .stream()
                .map(v -> new AgentApiDtos.TemplateVersionItem(v.getVersion(), v.getContent(),
                        v.getChangeSummary(), v.getIsImportant(), v.getCreatedAt()))
                .toList();
        return Result.ok(new AgentApiDtos.TemplateVersionsResp(id, items));
    }

    // ==================== 私有工具 ====================

    private List<Task> filteredTasks(Long familyId, String status, Long categoryId,
                                     String keyword, Long assigneeId) {
        TaskStatus st = status == null ? null : parseEnum(status, TaskStatus.class, "status");
        return taskRepository
                .findWithFilters(familyId, st, categoryId, null, keyword, null)
                .stream()
                .filter(t -> assigneeId == null || assigneeId.equals(t.getAssigneeId()))
                .toList();
    }

    private Task requireTask(Long id, Long familyId) {
        return taskRepository.findByIdAndFamilyId(id, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.TASK_NOT_FOUND));
    }

    private Subtask requireSubtask(Long id, Long familyId) {
        Subtask subtask = subtaskRepository.findById(id)
                .orElseThrow(() -> new ApiException(ResultCode.SUBTASK_NOT_FOUND));
        requireTask(subtask.getParentTaskId(), familyId);
        return subtask;
    }

    private void validateCategory(Long categoryId, Long familyId) {
        if (categoryId == null) {
            return;
        }
        categoryRepository.findByIdAndFamilyIdAndDeletedAtIsNull(categoryId, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.CATEGORY_MISMATCH));
    }

    private AgentApiDtos.TaskResp toTaskResp(Task t, boolean withChildren) {
        return new AgentApiDtos.TaskResp(
                t.getId(), t.getTitle(), t.getDescription(),
                t.getStatus() == null ? null : t.getStatus().name(),
                t.getPriority() == null ? null : t.getPriority().name(),
                t.getCategoryId(), t.getAssigneeId(), t.getDueDate(), t.getExpiresAt(),
                withChildren ? toSubtaskRespList(t.getId()) : null,
                withChildren ? toNoteRespList("task", t.getId()) : null);
    }

    private List<AgentApiDtos.SubtaskResp> toSubtaskRespList(Long parentTaskId) {
        return subtaskRepository.findByParentTaskIdOrderBySortOrderAscIdAsc(parentTaskId)
                .stream().map(this::toSubtaskResp).toList();
    }

    private AgentApiDtos.SubtaskResp toSubtaskResp(Subtask s) {
        return new AgentApiDtos.SubtaskResp(s.getId(), s.getParentTaskId(), s.getTitle(),
                s.getDescription(), s.getSortOrder(),
                s.getStatus() == null ? null : s.getStatus().name(), s.getIsAiGenerated());
    }

    private List<NoteResp> toNoteRespList(String targetType, Long targetId) {
        return noteRepository.findByTargetTypeAndTargetIdOrderByIdAsc(targetType, targetId)
                .stream().map(this::toNoteResp).toList();
    }

    private NoteResp toNoteResp(Note n) {
        return new NoteResp(n.getId(), n.getTargetType(), n.getTargetId(), n.getContent(),
                n.getCreatedAt());
    }

    private AgentApiDtos.TemplateResp toTemplateResp(RuleTemplate t) {
        return new AgentApiDtos.TemplateResp(t.getId(), t.getName(), t.getType(), t.getVersion(),
                t.getIsActive(), t.getConditions(), t.getContent(), t.getCategoryId());
    }

    private AgentApiDtos.ScheduleResp toScheduleResp(TaskSchedule s) {
        return new AgentApiDtos.ScheduleResp(s.getId(), s.getFamilyId(), s.getTriggerDate(),
                s.getTargetTemplate(), s.getIsExecuted());
    }

    private <E extends Enum<E>> E parseEnum(String raw, Class<E> type, String field) {
        try {
            return Enum.valueOf(type, raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        }
    }

    // ==================== B-3 ~ B-6 Change 8 新增：人员档案 + 拆解会话 Internal API ====================
    // （前缀 /api/internal/agent/**，由 InternalSecretInterceptor 校验 X-Internal-Secret）

    /**
     * B-3 POST /profiles/get-by-family：按 family_id 列该家庭所有未删除档案。
     * 供 agent-service A-3 profile-chat/stream 读取档案上下文传给 AI 做精准访谈。
     *
     * @param body {family_id: Long}
     * @return List<PersonProfileResponse>
     */
    @PostMapping("/profiles/get-by-family")
    @Transactional(readOnly = true)
    public Result<List<PersonProfileResponse>> b3_getProfilesByFamily(
            @RequestBody java.util.Map<String, Object> body) {
        Long familyId = getRequiredLong(body, "family_id", "B-3 family_id");
        // 不校验家庭存在性按 NOT_FOUND 拒绝（防枚举探测），不存在则返回空列表
        return Result.ok(
                personProfileRepository.findByFamilyIdAndDeletedAtIsNull(familyId)
                        .stream()
                        .map(PersonProfileResponse::from)
                        .toList());
    }

    /**
     * B-4 POST /profiles/patch-many：MVP 留占位（真正写档案走 D-5/D-6 内部确认端点）。
     * 返回 501 Not Implemented（JSON $.code=501 + HTTP 501）。
     * GlobalExceptionHandler.handleResult() 的 ResultException 会按 code==501 特判映射 HTTP 501。
     */
    @PostMapping("/profiles/patch-many")
    public Result<Object> b4_patchManyNotImplemented() {
        throw new ResultException(501, "暂未实现，MVP 请走 D-5/D-6 内部确认");
    }

    /**
     * B-5 POST /sessions/{id}/extract-patch：agent-service A-3 访谈完成后把 AI 推断的
     * profile patch 写回会话 extracted_profile_patch 列，供用户 D-5 确认时合并到档案。
     *
     * <p>仅 ACTIVE 会话允许写（已确认/取消/归档的会话报 AI_DECOMPOSE_DISABLED 等价码；
     * 具体精确 50311 码在 Task 2 DecomposeSessionController 实现）。
     *
     * @param body {extracted_patch: Object}
     */
    @PostMapping("/sessions/{id}/extract-patch")
    @Transactional
    public Result<Object> b5_saveExtractedPatch(
            @PathVariable Long id,
            @RequestParam("family_id") Long familyId,
            @RequestBody java.util.Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> patch = (java.util.Map<String, Object>) body.get("extracted_patch");
        if (patch == null) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "B-5 extracted_patch 必填");
        }
        DecomposeSession session = decomposeSessionRepository
                .findLockedScoped(id, familyId)
                .filter(s -> s.getStatus() == DecomposeSessionStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ResultCode.AI_PROPOSAL_NOT_FOUND,
                        "session id=" + id + " 不存在或非 ACTIVE 状态"));
        session.setExtractedProfilePatch(new java.util.HashMap<>(patch));
        decomposeSessionRepository.save(session);
        return Result.ok(null);
    }

    /**
     * B-6 POST /sessions/{id}/save-proposal：agent-service A-4 拆解完成后把
     * proposed_tasks JSON 数组写回会话，供用户 D-5 确认时批量写入 tasks 表。
     *
     * <p>仅 ACTIVE 会话允许写。
     *
     * @param body {proposed_tasks: List<Object>}
     */
    /** AI 只读取当前家庭会话上下文，历史对话不由浏览器伪造。 */
    @GetMapping("/sessions/{id}/context")
    @Transactional(readOnly = true)
    public Result<Object> sessionContext(@PathVariable Long id, @RequestParam("family_id") Long familyId) {
        DecomposeSession s = decomposeSessionRepository.findByIdAndFamilyId(id, familyId)
                .orElseThrow(() -> new ApiException(ResultCode.AI_PROPOSAL_NOT_FOUND));
        if (s.getStatus() != DecomposeSessionStatus.ACTIVE) {
            throw new ApiException(ResultCode.AI_PROPOSAL_ALREADY_FINALIZED);
        }
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("session_type", s.getSessionType() == null ? null : s.getSessionType().name());
        data.put("title", s.getInitTaskTitle());
        data.put("description", s.getInitTaskDescription());
        data.put("due_date", s.getInitTaskDueDate());
        data.put("category_id", s.getInitTaskCategoryId());
        data.put("priority", s.getInitTaskPriority());
        data.put("assignee_profile_id", s.getProfileId());
        data.put("messages", s.getMessages());
        data.put("proposed_tasks", s.getProposedTasks());
        data.put("revision", s.getUpdatedAt().toString());
        return Result.ok(data);
    }

    @PostMapping("/sessions/{id}/save-proposal")
    @Transactional
    public Result<Object> b6_saveProposedTasks(
            @PathVariable Long id,
            @RequestParam("family_id") Long familyId,
            @RequestBody java.util.Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        java.util.List<Object> tasks = (java.util.List<Object>) body.get("proposed_tasks");
        if (tasks == null) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "B-6 proposed_tasks 必填");
        }
        DecomposeSession session = decomposeSessionRepository
                .findLockedScoped(id, familyId).filter(s -> s.getStatus() == DecomposeSessionStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ResultCode.AI_PROPOSAL_NOT_FOUND,
                        "session id=" + id + " 不存在或非 ACTIVE 状态"));
        if (!familyId.equals(session.getFamilyId())) {
            throw new ApiException(ResultCode.AI_PROPOSAL_NOT_FOUND);
        }
        Object revision = body.get("revision");
        if (revision != null && !session.getUpdatedAt().equals(java.time.Instant.parse(revision.toString()))) {
            throw new ApiException(ResultCode.AI_PROPOSAL_ALREADY_FINALIZED, "草稿已经更新，请重新读取后再修改");
        }
        if (body.get("message") instanceof String message && !message.isBlank()) {
            java.util.List<Object> messages = new java.util.ArrayList<>(session.getMessages());
            messages.add(java.util.Map.of("role", "user", "content", message));
            session.setMessages(messages.size() > 20 ? new java.util.ArrayList<>(messages.subList(messages.size() - 20, messages.size())) : messages);
        }
        session.setProposedTasks(new java.util.ArrayList<>(tasks));
        decomposeSessionRepository.save(session);
        return Result.ok(null);
    }

    /**
     * B-7 POST /sessions/{id}/messages：agent-service 访谈轮次结束后把用户消息与助手回复
     * 追加到会话 messages（B-5 只写补丁不写对话），供后续轮次读取上下文。
     *
     * <p>仅 ACTIVE 会话允许写；messages 超过 20 条时裁剪最旧（与 B-6 一致）。
     *
     * @param body {role: "user"|"assistant", content: String}
     */
    @PostMapping("/sessions/{id}/messages")
    @Transactional
    public Result<Object> b7_appendMessage(
            @PathVariable Long id,
            @RequestParam("family_id") Long familyId,
            @RequestBody java.util.Map<String, Object> body) {
        String role = body.get("role") instanceof String r ? r : null;
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "B-7 role 必须为 user 或 assistant");
        }
        String content = body.get("content") instanceof String c ? c.trim() : null;
        if (content == null || content.isEmpty()) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, "B-7 content 必填");
        }
        if (content.length() > 4000) {
            content = content.substring(0, 4000);
        }
        DecomposeSession session = decomposeSessionRepository
                .findLockedScoped(id, familyId)
                .filter(s -> s.getStatus() == DecomposeSessionStatus.ACTIVE)
                .orElseThrow(() -> new ApiException(ResultCode.AI_PROPOSAL_NOT_FOUND,
                        "session id=" + id + " 不存在或非 ACTIVE 状态"));
        java.util.List<Object> messages = new java.util.ArrayList<>(session.getMessages());
        messages.add(java.util.Map.of("role", role, "content", content));
        session.setMessages(messages.size() > 20
                ? new java.util.ArrayList<>(messages.subList(messages.size() - 20, messages.size()))
                : messages);
        decomposeSessionRepository.save(session);
        return Result.ok(null);
    }

    /** B 组 body 参数工具：必填 Long 字段提取 */
    private Long getRequiredLong(java.util.Map<String, Object> body, String key, String fieldName) {
        Object v = body.get(key);
        if (v == null) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, fieldName + " 必填");
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION, fieldName + " 格式不正确");
        }
    }
}
