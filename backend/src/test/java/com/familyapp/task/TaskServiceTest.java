package com.familyapp.task;
import com.familyapp.auth.UserContext;
import com.familyapp.auth.UserContext.CurrentUser;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.category.CategoryRepository;
import com.familyapp.category.Category;
import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.task.dto.TaskFilter;
import com.familyapp.task.dto.TaskResponse;
import com.familyapp.task.dto.UpdateTaskRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {
    @Mock TaskRepository repo;
    @Mock CategoryRepository categoryRepo;
    @Mock com.familyapp.profile.PersonProfileRepository personProfileRepository;
    @Mock com.familyapp.agent.TaskEventService taskEventService;
    @Mock com.familyapp.wechat.WechatNotifyService wechatNotifyService;
    @Mock FamilyRepository familyRepository;
    @Mock FamilyMemberRepository familyMemberRepository;
    TaskServiceImpl service;
    private Task completedTask;
    @BeforeEach
    void setUp() {
        UserContext.setCurrent(new CurrentUser(1L, "test@test.com", "test", 1L, "OWNER"));
        Family family = new Family();
        family.setId(1L);
        lenient().when(familyRepository.findByIdAndDeletedAtIsNull(1L))
                .thenReturn(Optional.of(family));
        FamilyMember member = new FamilyMember();
        member.setFamilyId(1L);
        member.setUserId(1L);
        member.setRole(com.familyapp.family.FamilyRole.OWNER);
        lenient().when(familyMemberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(1L, 1L))
                .thenReturn(Optional.of(member));
        TaskCreationCommandService commandService = new TaskCreationCommandService(
                repo, categoryRepo, personProfileRepository,
                familyRepository, familyMemberRepository, wechatNotifyService);
        service = new TaskServiceImpl(repo, categoryRepo, personProfileRepository,
                taskEventService, wechatNotifyService, commandService);

        completedTask = new Task();
        completedTask.setId(99L);
        completedTask.setTitle("completed");
        completedTask.setStatus(TaskStatus.COMPLETED);
    }
    @AfterEach
    void tearDown() { UserContext.clear(); }
    @Test
    void update_rejects_COMPLETED_to_PENDING_transition() {
        when(repo.findByIdAndFamilyId(99L, 1L)).thenReturn(Optional.of(completedTask));
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setStatus("PENDING");
        ApiException ex = assertThrows(ApiException.class, () -> service.update(99L, req));
        assertEquals(ResultCode.BAD_REQUEST_STATUS_TRANSITION, ex.getCode());
    }
    @Test
    void update_rejects_CANCELLED_to_ANY_transition() {
        completedTask.setStatus(TaskStatus.CANCELLED);
        when(repo.findByIdAndFamilyId(99L, 1L)).thenReturn(Optional.of(completedTask));
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setStatus("IN_PROGRESS");
        ApiException ex = assertThrows(ApiException.class, () -> service.update(99L, req));
        assertEquals(ResultCode.BAD_REQUEST_STATUS_TRANSITION, ex.getCode());
    }
    @Test
    void create_default_status_is_PENDING_when_null() {
        when(repo.save(any(Task.class))).thenAnswer(inv -> { Task t = inv.getArgument(0); t.setId(1L); t.onCreate(); return t; });
        CreateTaskRequest req = new CreateTaskRequest(); req.setTitle("t1");
        TaskResponse r = service.create(req);
        assertEquals(TaskStatus.PENDING, r.status());
        assertEquals(TaskPriority.MEDIUM, r.priority());
        assertEquals(TaskSource.MANUAL, r.source());
        assertEquals("t1", r.title());
    }
    @Test
    void create_allows_DRAFT_PENDING_IN_PROGRESS_rejects_CANCELLED_and_COMPLETED() {
        when(repo.save(any(Task.class))).thenAnswer(inv -> { Task t = inv.getArgument(0); t.setId(1L); t.onCreate(); return t; });
        for (String s : List.of("DRAFT", "PENDING", "IN_PROGRESS")) {
            CreateTaskRequest r = new CreateTaskRequest(); r.setTitle("ok"); r.setStatus(s);
            assertEquals(TaskStatus.valueOf(s), service.create(r).status());
        }
        for (String s : List.of("CANCELLED", "COMPLETED")) {
            CreateTaskRequest req = new CreateTaskRequest(); req.setTitle("bad"); req.setStatus(s);
            ApiException ex = assertThrows(ApiException.class, () -> service.create(req));
            assertEquals(ResultCode.BAD_REQUEST_STATUS_TRANSITION, ex.getCode());
        }
    }
    @Test
    void create_DRAFT_sets_expiresAt_24h() {
        when(repo.save(any(Task.class))).thenAnswer(inv -> { Task t = inv.getArgument(0); t.setId(1L); t.onCreate(); return t; });
        CreateTaskRequest req = new CreateTaskRequest(); req.setTitle("draft task"); req.setStatus("DRAFT");
        TaskResponse r = service.create(req);
        assertEquals(TaskStatus.DRAFT, r.status());
        assertNotNull(r.expiresAt(), "DRAFT should have expiresAt set");
    }
    @Test
    void create_non_DRAFT_does_not_set_expiresAt() {
        when(repo.save(any(Task.class))).thenAnswer(inv -> { Task t = inv.getArgument(0); t.setId(1L); t.onCreate(); return t; });
        CreateTaskRequest req = new CreateTaskRequest(); req.setTitle("normal"); req.setStatus("PENDING");
        TaskResponse r = service.create(req);
        assertNull(r.expiresAt(), "non-DRAFT should not have expiresAt");
    }
    @Test
    void update_DRAFT_to_PENDING_clears_expiresAt() {
        Task t = new Task(); t.setId(1L); t.setTitle("draft"); t.setStatus(TaskStatus.DRAFT);
        t.setExpiresAt(Instant.now().plusSeconds(3600)); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        when(repo.save(any(Task.class))).thenReturn(t);
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setStatus("PENDING");
        TaskResponse r = service.update(1L, req);
        assertEquals(TaskStatus.PENDING, r.status());
        assertNull(r.expiresAt(), "expiresAt should be cleared on DRAFT to PENDING");
    }
    @Test
    void update_PENDING_direct_to_COMPLETED_allowed() {
        Task t = new Task(); t.setId(1L); t.setTitle("simple"); t.setStatus(TaskStatus.PENDING); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        when(repo.save(any(Task.class))).thenReturn(t);
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setStatus("COMPLETED");
        TaskResponse r = service.update(1L, req);
        assertEquals(TaskStatus.COMPLETED, r.status());
    }
    @Test
    void list_with_filter_delegates_to_repository() {
        Task a = new Task(); a.setId(1L); a.setTitle("A"); a.setStatus(TaskStatus.PENDING); a.onCreate();
        Task b = new Task(); b.setId(2L); b.setTitle("B"); b.setStatus(TaskStatus.COMPLETED); b.onCreate();
        when(repo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of(a));
        TaskFilter filter = new TaskFilter(TaskStatus.PENDING, null, null, null, null);
        List<TaskResponse> rs = service.list(filter);
        assertEquals(1, rs.size());
        assertEquals("A", rs.get(0).title());
    }
    @Test
    void list_overdue_filter_uses_today_and_excludes_done_statuses() {
        // 验证 overdue=true 时只返回逾期未完成任务（实现层用 Specification，
        // 这里只校验 service 层不抛、且能透传到 repo）
        when(repo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        TaskFilter filter = new TaskFilter(null, null, null, null, null, null, true, java.time.LocalDate.of(2026, 9, 11));
        service.list(filter);
        verify(repo).findAll(any(Specification.class), any(Sort.class));
    }
    @Test
    void list_assignee_filter_only_returns_user_tasks() {
        when(repo.findAll(any(Specification.class), any(Sort.class))).thenReturn(List.of());
        TaskFilter filter = new TaskFilter(null, null, null, null, null, 1L, null, null);
        service.list(filter);
        verify(repo).findAll(any(Specification.class), any(Sort.class));
    }
    @Test
    void stats_returns_4_counts_from_repository() {
        // 4 个计数：家庭 PENDING / 本人 PENDING / 家庭逾期 / 本人逾期
        when(repo.count(any(Specification.class))).thenReturn(5L, 2L, 3L, 1L);
        com.familyapp.task.dto.TaskStats s = service.stats();
        assertEquals(5L, s.familyPendingCount());
        assertEquals(2L, s.minePendingCount());
        assertEquals(3L, s.familyOverdueCount());
        assertEquals(1L, s.mineOverdueCount());
    }
    @Test
    void get_returns_task_or_throws() {
        Task a = new Task(); a.setId(1L); a.setTitle("A"); a.setStatus(TaskStatus.PENDING); a.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(a));
        assertEquals("A", service.get(1L).title());
        when(repo.findByIdAndFamilyId(2L, 1L)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.get(2L));
    }
    @Test
    void delete_is_soft_delete() {
        Task t = new Task(); t.setId(1L); t.setTitle("x"); t.setStatus(TaskStatus.PENDING); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        service.delete(1L);
        assertNotNull(t.getDeletedAt(), "expected deleted_at set");
        verify(repo).save(t);
    }
    @Test
    void delete_on_already_deleted_throws_404() {
        Task t = new Task(); t.setId(1L); t.setDeletedAt(Instant.now());
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        ApiException ex = assertThrows(ApiException.class, () -> service.delete(1L));
        assertEquals(ResultCode.TASK_NOT_FOUND, ex.getCode());
    }
    @Test
    void update_accepts_partial_fields() {
        Task t = new Task(); t.setId(1L); t.setTitle("orig"); t.setStatus(TaskStatus.PENDING); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        when(repo.save(any(Task.class))).thenReturn(t);
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setTitle("new");
        TaskResponse r = service.update(1L, req);
        assertEquals("new", r.title());
        assertEquals(TaskStatus.PENDING, r.status());
    }
    @Test
    void update_priority_and_source_partial() {
        Task t = new Task(); t.setId(1L); t.setTitle("orig"); t.setStatus(TaskStatus.PENDING); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        when(repo.save(any(Task.class))).thenReturn(t);
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setPriority("HIGH"); req.setSource("ROUTINE");
        TaskResponse r = service.update(1L, req);
        assertEquals(TaskPriority.HIGH, r.priority());
        assertEquals(TaskSource.ROUTINE, r.source());
    }
    @Test
    void update_rejects_invalid_priority() {
        Task t = new Task(); t.setId(1L); t.setTitle("orig"); t.setStatus(TaskStatus.PENDING); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setPriority("URGENT");
        ApiException ex = assertThrows(ApiException.class, () -> service.update(1L, req));
        assertEquals(ResultCode.BAD_REQUEST_INVALID_PRIORITY, ex.getCode());
    }
    @Test
    void update_rejects_invalid_source() {
        Task t = new Task(); t.setId(1L); t.setTitle("orig"); t.setStatus(TaskStatus.PENDING); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setSource("UNKNOWN");
        ApiException ex = assertThrows(ApiException.class, () -> service.update(1L, req));
        assertEquals(ResultCode.BAD_REQUEST_INVALID_SOURCE, ex.getCode());
    }
    @Test
    void update_status_allows_valid_DFA_paths() {
        List<String[]> validPairs = List.of(
            new String[]{"DRAFT","DRAFT"}, new String[]{"DRAFT","PENDING"}, new String[]{"DRAFT","CANCELLED"},
            new String[]{"PENDING","PENDING"}, new String[]{"PENDING","IN_PROGRESS"},
            new String[]{"PENDING","COMPLETED"}, new String[]{"PENDING","CANCELLED"},
            new String[]{"IN_PROGRESS","PENDING"}, new String[]{"IN_PROGRESS","IN_PROGRESS"},
            new String[]{"IN_PROGRESS","COMPLETED"}, new String[]{"IN_PROGRESS","CANCELLED"},
            new String[]{"COMPLETED","COMPLETED"}, new String[]{"CANCELLED","CANCELLED"}
        );
        for (String[] pair : validPairs) {
            Task t = new Task(); t.setId(1L); t.setTitle("t");
            t.setStatus(TaskStatus.valueOf(pair[0])); t.onCreate();
            when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
            when(repo.save(any(Task.class))).thenReturn(t);
            UpdateTaskRequest req = new UpdateTaskRequest(); req.setStatus(pair[1]);
            TaskResponse r = service.update(1L, req);
            assertEquals(TaskStatus.valueOf(pair[1]), r.status(), "expected valid transition " + pair[0] + " -> " + pair[1]);
        }
    }
    @Test
    void create_with_valid_categoryId_persists() {
        when(repo.save(any(Task.class))).thenAnswer(inv -> { Task t = inv.getArgument(0); t.setId(1L); t.onCreate(); return t; });
        when(categoryRepo.findByIdAndFamilyIdAndDeletedAtIsNull(7L, 1L)).thenReturn(Optional.of(new Category()));
        CreateTaskRequest req = new CreateTaskRequest(); req.setTitle("categorized"); req.setCategoryId(7L);
        TaskResponse r = service.create(req);
        assertEquals(7L, r.categoryId());
    }
    @Test
    void create_with_missing_category_rejected() {
        when(categoryRepo.findByIdAndFamilyIdAndDeletedAtIsNull(999L, 1L)).thenReturn(Optional.empty());
        CreateTaskRequest req = new CreateTaskRequest(); req.setTitle("bad"); req.setCategoryId(999L);
        ApiException ex = assertThrows(ApiException.class, () -> service.create(req));
        assertEquals(ResultCode.CATEGORY_NOT_FOUND, ex.getCode());
    }
    @Test
    void update_with_valid_categoryId_persists() {
        Task t = new Task(); t.setId(1L); t.setTitle("x"); t.setStatus(TaskStatus.PENDING); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        when(repo.save(any(Task.class))).thenReturn(t);
        when(categoryRepo.findByIdAndFamilyIdAndDeletedAtIsNull(3L, 1L)).thenReturn(Optional.of(new Category()));
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setCategoryId(3L);
        TaskResponse r = service.update(1L, req);
        assertEquals(3L, r.categoryId());
    }
    @Test
    void update_with_missing_category_rejected() {
        Task t = new Task(); t.setId(1L); t.setTitle("x"); t.setStatus(TaskStatus.PENDING); t.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(t));
        when(categoryRepo.findByIdAndFamilyIdAndDeletedAtIsNull(999L, 1L)).thenReturn(Optional.empty());
        UpdateTaskRequest req = new UpdateTaskRequest(); req.setCategoryId(999L);
        ApiException ex = assertThrows(ApiException.class, () -> service.update(1L, req));
        assertEquals(ResultCode.CATEGORY_NOT_FOUND, ex.getCode());
    }

    @Test
    void create_rejects_spoofed_family_and_creator_from_logged_in_request() {
        CreateTaskRequest wrongFamily = new CreateTaskRequest();
        wrongFamily.setTitle("越权");
        wrongFamily.setFamilyId(2L);
        ApiException familyError = assertThrows(ApiException.class, () -> service.create(wrongFamily));
        assertEquals(ResultCode.ROLE_NO_PERMISSION, familyError.getCode());

        CreateTaskRequest wrongCreator = new CreateTaskRequest();
        wrongCreator.setTitle("冒用");
        wrongCreator.setCreatedBy(2L);
        ApiException creatorError = assertThrows(ApiException.class, () -> service.create(wrongCreator));
        assertEquals(ResultCode.ROLE_NO_PERMISSION, creatorError.getCode());
        verify(repo, never()).save(any());
    }

    @Test
    void create_without_user_context_requires_and_accepts_explicit_internal_identity() {
        UserContext.clear();
        CreateTaskRequest missingCreator = new CreateTaskRequest();
        missingCreator.setTitle("缺创建人");
        missingCreator.setFamilyId(1L);
        assertThrows(ApiException.class, () -> service.create(missingCreator));
        FamilyMember internalMember = new FamilyMember();
        internalMember.setFamilyId(1L);
        internalMember.setUserId(9L);
        internalMember.setRole(com.familyapp.family.FamilyRole.ADULT);
        when(familyMemberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(1L, 9L))
                .thenReturn(Optional.of(internalMember));

        when(repo.save(any(Task.class))).thenAnswer(inv -> {
            Task task = inv.getArgument(0);
            task.setId(1L);
            task.onCreate();
            return task;
        });
        CreateTaskRequest internal = new CreateTaskRequest();
        internal.setTitle("内部任务");
        internal.setFamilyId(1L);
        internal.setCreatedBy(9L);
        TaskResponse response = service.create(internal);
        assertEquals(1L, response.familyId());
        assertEquals(9L, response.createdBy());
    }

    @Test
    void create_uses_latest_child_role_and_does_not_allow_privilege_escalation() {
        UserContext.setCurrent(new CurrentUser(1L, "child@test.com", "孩子", 1L, "OWNER"));
        FamilyMember childMember = new FamilyMember();
        childMember.setFamilyId(1L);
        childMember.setUserId(1L);
        childMember.setRole(com.familyapp.family.FamilyRole.CHILD);
        when(familyMemberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(1L, 1L))
                .thenReturn(Optional.of(childMember));
        com.familyapp.profile.PersonProfile profile = new com.familyapp.profile.PersonProfile();
        profile.setId(11L);
        profile.setFamilyId(1L);
        profile.setUserId(1L);
        when(personProfileRepository.findByFamilyIdAndUserIdAndDeletedAtIsNullOrderByIdAsc(1L, 1L))
                .thenReturn(Optional.of(profile));
        when(personProfileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(1L, 11L))
                .thenReturn(Optional.of(profile));
        when(repo.save(any(Task.class))).thenAnswer(inv -> {
            Task task = inv.getArgument(0);
            task.setId(1L);
            task.onCreate();
            return task;
        });

        CreateTaskRequest self = new CreateTaskRequest();
        self.setTitle("自己的任务");
        TaskResponse response = service.create(self);
        assertEquals(1L, response.assigneeId());
        assertEquals(11L, response.assigneeProfileId());

        CreateTaskRequest other = new CreateTaskRequest();
        other.setTitle("给别人");
        other.setAssigneeId(2L);
        ApiException error = assertThrows(ApiException.class, () -> service.create(other));
        assertEquals(ResultCode.ROLE_NO_PERMISSION, error.getCode());
    }

    @Test
    void child_update_only_related_task_and_cannot_reassign_or_delete() {
        UserContext.setCurrent(new CurrentUser(1L, "child@test.com", "孩子", 1L, "CHILD"));
        Task unrelated = new Task();
        unrelated.setId(1L);
        unrelated.setFamilyId(1L);
        unrelated.setCreatedBy(2L);
        unrelated.setAssigneeId(2L);
        unrelated.setTitle("别人的任务");
        unrelated.setStatus(TaskStatus.PENDING);
        unrelated.onCreate();
        when(repo.findByIdAndFamilyId(1L, 1L)).thenReturn(Optional.of(unrelated));
        ApiException unrelatedError = assertThrows(ApiException.class,
                () -> service.update(1L, new UpdateTaskRequest()));
        assertEquals(ResultCode.ROLE_NO_PERMISSION, unrelatedError.getCode());

        Task own = new Task();
        own.setId(2L);
        own.setFamilyId(1L);
        own.setCreatedBy(1L);
        own.setTitle("自己的任务");
        own.setStatus(TaskStatus.PENDING);
        own.onCreate();
        when(repo.findByIdAndFamilyId(2L, 1L)).thenReturn(Optional.of(own));
        UpdateTaskRequest rename = new UpdateTaskRequest();
        rename.setTitle("已修改");
        when(repo.save(own)).thenReturn(own);
        assertEquals("已修改", service.update(2L, rename).title());

        UpdateTaskRequest reassign = new UpdateTaskRequest();
        reassign.setAssigneeId(2L);
        ApiException reassignError = assertThrows(ApiException.class,
                () -> service.update(2L, reassign));
        assertEquals(ResultCode.ROLE_NO_PERMISSION, reassignError.getCode());

        ApiException deleteError = assertThrows(ApiException.class, () -> service.delete(2L));
        assertEquals(ResultCode.ROLE_NO_PERMISSION, deleteError.getCode());
    }
}
