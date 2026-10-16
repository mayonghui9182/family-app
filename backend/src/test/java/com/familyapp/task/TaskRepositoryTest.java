package com.familyapp.task;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
@DataJpaTest
@ActiveProfiles("test")
class TaskRepositoryTest {
    @Autowired TaskRepository repo;
    @PersistenceContext EntityManager em;
    private static final Long FAMILY_ID = 1L;
    @Test
    void findById_skips_soft_deleted_rows() {
        Task t = new Task(); t.setTitle("x"); t.setStatus(TaskStatus.PENDING);
        t.setFamilyId(FAMILY_ID); t.setDeletedAt(Instant.now());
        Task saved = repo.save(t);
        em.flush(); em.clear();
        Optional<Task> byId = repo.findById(saved.getId());
        assertFalse(byId.isPresent(), "@Where should filter deleted_at IS NOT NULL");
        Optional<Task> include = repo.findByIdIncludeDeleted(saved.getId());
        assertTrue(include.isPresent(), "findByIdIncludeDeleted bypasses @Where");
    }
    @Test
    void findById_return_not_deleted_rows() {
        Task t = new Task(); t.setTitle("a"); t.setStatus(TaskStatus.PENDING); t.setFamilyId(FAMILY_ID);
        Task saved = repo.save(t); em.flush(); em.clear();
        Optional<Task> byId = repo.findById(saved.getId());
        assertTrue(byId.isPresent());
        assertEquals("a", byId.get().getTitle());
    }
    @Test
    void findAll_excludes_deleted_rows() {
        Task ok1 = new Task(); ok1.setTitle("ok1"); ok1.setStatus(TaskStatus.PENDING); ok1.setFamilyId(FAMILY_ID);
        Task ok2 = new Task(); ok2.setTitle("ok2"); ok2.setStatus(TaskStatus.PENDING); ok2.setFamilyId(FAMILY_ID);
        Task del = new Task(); del.setTitle("del"); del.setStatus(TaskStatus.PENDING); del.setFamilyId(FAMILY_ID);
        del.setDeletedAt(Instant.now());
        repo.save(ok1); repo.save(ok2); repo.save(del); em.flush(); em.clear();
        assertEquals(2, repo.findAll().size());
    }
    @Test
    void findWithFilters_by_status() {
        Task p = new Task(); p.setTitle("pending"); p.setStatus(TaskStatus.PENDING); p.setFamilyId(FAMILY_ID);
        Task c = new Task(); c.setTitle("completed"); c.setStatus(TaskStatus.COMPLETED); c.setFamilyId(FAMILY_ID);
        repo.save(p); repo.save(c); em.flush(); em.clear();
        List<Task> result = repo.findWithFilters(FAMILY_ID, TaskStatus.PENDING, null, null, null, null);
        assertEquals(1, result.size());
        assertEquals("pending", result.get(0).getTitle());
    }
    @Test
    void findWithFilters_by_keyword() {
        Task t1 = new Task(); t1.setTitle("buy milk"); t1.setStatus(TaskStatus.PENDING); t1.setFamilyId(FAMILY_ID);
        Task t2 = new Task(); t2.setTitle("clean floor"); t2.setStatus(TaskStatus.PENDING); t2.setFamilyId(FAMILY_ID);
        repo.save(t1); repo.save(t2); em.flush(); em.clear();
        List<Task> result = repo.findWithFilters(FAMILY_ID, null, null, null, "milk", null);
        assertEquals(1, result.size());
        assertEquals("buy milk", result.get(0).getTitle());
    }
    @Test
    void findWithFilters_multi_and() {
        Task t1 = new Task(); t1.setTitle("task1"); t1.setStatus(TaskStatus.PENDING); t1.setFamilyId(FAMILY_ID); t1.setSource(TaskSource.MANUAL);
        Task t2 = new Task(); t2.setTitle("task2"); t2.setStatus(TaskStatus.PENDING); t2.setFamilyId(FAMILY_ID); t2.setSource(TaskSource.AGENT);
        repo.save(t1); repo.save(t2); em.flush(); em.clear();
        List<Task> result = repo.findWithFilters(FAMILY_ID, TaskStatus.PENDING, null, TaskSource.MANUAL, null, null);
        assertEquals(1, result.size());
        assertEquals("task1", result.get(0).getTitle());
    }
    @Test
    void findWithFilters_by_dueBefore() {
        Task t1 = new Task(); t1.setTitle("early"); t1.setStatus(TaskStatus.PENDING); t1.setFamilyId(FAMILY_ID);
        t1.setDueDate(LocalDate.of(2026, 8, 31));
        Task t2 = new Task(); t2.setTitle("late"); t2.setStatus(TaskStatus.PENDING); t2.setFamilyId(FAMILY_ID);
        t2.setDueDate(LocalDate.of(2026, 9, 2));
        repo.save(t1); repo.save(t2); em.flush(); em.clear();
        List<Task> result = repo.findWithFilters(FAMILY_ID, null, null, null, null, LocalDate.of(2026, 9, 1));
        assertEquals(1, result.size());
        assertEquals("early", result.get(0).getTitle());
    }
    @Test
    void findByStatusAndExpiresAtBefore_finds_expired_drafts() {
        Task d = new Task(); d.setTitle("draft"); d.setStatus(TaskStatus.DRAFT); d.setFamilyId(FAMILY_ID);
        d.setExpiresAt(Instant.now().minusSeconds(3600));
        Task p = new Task(); p.setTitle("pending"); p.setStatus(TaskStatus.PENDING); p.setFamilyId(FAMILY_ID);
        repo.save(d); repo.save(p); em.flush(); em.clear();
        List<Task> expired = repo.findByStatusAndExpiresAtBefore(TaskStatus.DRAFT, Instant.now());
        assertEquals(1, expired.size());
        assertEquals("draft", expired.get(0).getTitle());
    }
}
