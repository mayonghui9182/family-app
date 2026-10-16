package com.familyapp.task;

import com.familyapp.auth.UserContext;
import com.familyapp.auth.UserContext.CurrentUser;
import com.familyapp.category.CategoryRepository;
import com.familyapp.task.dto.TaskFilter;
import com.familyapp.task.dto.TaskResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
class V6DraftFilterTest {

    @Autowired
    TaskRepository repo;

    @Mock
    CategoryRepository categoryRepo;

    @PersistenceContext
    EntityManager em;

    private TaskServiceImpl service;

    private static final Long FAMILY_ID = 1L;

    @BeforeEach
    void setUp() {
        UserContext.setCurrent(new CurrentUser(1L, "test@test.com", "test", FAMILY_ID, "OWNER"));
        service = new TaskServiceImpl(repo, categoryRepo,
                org.mockito.Mockito.mock(com.familyapp.profile.PersonProfileRepository.class),
                org.mockito.Mockito.mock(com.familyapp.agent.TaskEventService.class),
                org.mockito.Mockito.mock(com.familyapp.wechat.WechatNotifyService.class),
                org.mockito.Mockito.mock(TaskCreationCommandService.class));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void list_default_hides_expired_draft() {
        Task expiredDraft = new Task();
        expiredDraft.setTitle("expired-draft");
        expiredDraft.setStatus(TaskStatus.DRAFT);
        expiredDraft.setFamilyId(FAMILY_ID);
        expiredDraft.setExpiresAt(Instant.now().minusSeconds(2 * 3600));

        Task pending = new Task();
        pending.setTitle("pending-task");
        pending.setStatus(TaskStatus.PENDING);
        pending.setFamilyId(FAMILY_ID);

        repo.save(expiredDraft);
        repo.save(pending);
        em.flush();
        em.clear();

        TaskFilter filter = new TaskFilter(null, null, null, null, null);
        List<TaskResponse> rs = service.list(filter);

        assertEquals(1, rs.size(), "expired DRAFT should be filtered by default");
        assertEquals("pending-task", rs.get(0).title());
    }

    @Test
    void list_default_shows_valid_draft() {
        Task validDraft = new Task();
        validDraft.setTitle("valid-draft");
        validDraft.setStatus(TaskStatus.DRAFT);
        validDraft.setFamilyId(FAMILY_ID);
        validDraft.setExpiresAt(Instant.now().plusSeconds(12 * 3600));

        Task pending = new Task();
        pending.setTitle("pending-task");
        pending.setStatus(TaskStatus.PENDING);
        pending.setFamilyId(FAMILY_ID);

        repo.save(validDraft);
        repo.save(pending);
        em.flush();
        em.clear();

        TaskFilter filter = new TaskFilter(null, null, null, null, null);
        List<TaskResponse> rs = service.list(filter);

        assertEquals(2, rs.size(), "valid DRAFT should be displayed");
    }

    @Test
    void list_explicit_status_draft_only_returns_valid() {
        Task expiredDraft = new Task();
        expiredDraft.setTitle("expired-draft");
        expiredDraft.setStatus(TaskStatus.DRAFT);
        expiredDraft.setFamilyId(FAMILY_ID);
        expiredDraft.setExpiresAt(Instant.now().minusSeconds(2 * 3600));

        Task validDraft = new Task();
        validDraft.setTitle("valid-draft");
        validDraft.setStatus(TaskStatus.DRAFT);
        validDraft.setFamilyId(FAMILY_ID);
        validDraft.setExpiresAt(Instant.now().plusSeconds(12 * 3600));

        repo.save(expiredDraft);
        repo.save(validDraft);
        em.flush();
        em.clear();

        TaskFilter filter = new TaskFilter(TaskStatus.DRAFT, null, null, null, null);
        List<TaskResponse> rs = service.list(filter);

        assertEquals(1, rs.size(), "explicit status=DRAFT should still filter expired");
        assertEquals("valid-draft", rs.get(0).title());
    }

    @Test
    void list_excludes_soft_deleted_tasks() {
        Task deleted = new Task();
        deleted.setTitle("deleted-task");
        deleted.setStatus(TaskStatus.PENDING);
        deleted.setFamilyId(FAMILY_ID);
        deleted.setDeletedAt(Instant.now());

        Task pending = new Task();
        pending.setTitle("pending-task");
        pending.setStatus(TaskStatus.PENDING);
        pending.setFamilyId(FAMILY_ID);

        repo.save(deleted);
        repo.save(pending);
        em.flush();
        em.clear();

        TaskFilter filter = new TaskFilter(null, null, null, null, null);
        List<TaskResponse> rs = service.list(filter);

        assertEquals(1, rs.size(), "soft-deleted tasks should be excluded from list()");
        assertEquals("pending-task", rs.get(0).title());
    }

    @Test
    void list_respects_familyId_boundary() {
        Long otherFamilyId = 999L;

        Task otherFamily = new Task();
        otherFamily.setTitle("other-family-task");
        otherFamily.setStatus(TaskStatus.PENDING);
        otherFamily.setFamilyId(otherFamilyId);

        Task myFamily = new Task();
        myFamily.setTitle("my-family-task");
        myFamily.setStatus(TaskStatus.PENDING);
        myFamily.setFamilyId(FAMILY_ID);

        repo.save(otherFamily);
        repo.save(myFamily);
        em.flush();
        em.clear();

        TaskFilter filter = new TaskFilter(null, null, null, null, null);
        List<TaskResponse> rs = service.list(filter);

        assertEquals(1, rs.size(), "tasks from other familyId should NOT be returned");
        assertEquals("my-family-task", rs.get(0).title());
    }
}