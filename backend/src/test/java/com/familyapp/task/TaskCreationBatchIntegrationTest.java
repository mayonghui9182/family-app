package com.familyapp.task;

import com.familyapp.common.ApiException;
import com.familyapp.task.dto.CreateTaskRequest;
import com.familyapp.wechat.TaskNotificationJob;
import com.familyapp.wechat.WechatNotifyService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 不使用测试外层事务，直接验证服务的真实提交、回滚及独立发送事务可见性。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:taskcreationbatch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "wechat.notify.enabled=false"
})
@ActiveProfiles("test")
@Sql("classpath:db/v6-test-setup.sql")
class TaskCreationBatchIntegrationTest {

    @Autowired TaskCreationCommandService commands;
    @SpyBean TaskRepository tasks;
    @SpyBean WechatNotifyService notifications;
    @Autowired TaskNotificationJob dispatcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @PersistenceContext EntityManager entityManager;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM task_notifications WHERE family_id = 1");
        jdbc.update("DELETE FROM tasks WHERE family_id = 1");
    }

    @Test
    void invalidSecondRequestDoesNotStartWriting() {
        CreateTaskRequest invalid = request("第二条");
        invalid.setCategoryId(999L);

        assertThrows(ApiException.class, () -> commands.createPendingAgentBatch(
                1L, 1L, List.of(request("第一条"), invalid)));

        verify(tasks, never()).save(any(Task.class));
        verify(notifications, never()).enqueueTaskAssigned(any());
        assertEmptyBatch();
    }

    @Test
    void secondPersistenceFailureRollsBackFirstTask() {
        // 只在隔离 H2 库增加故障约束，让第二次真实 INSERT 失败，验证第一行实际回滚。
        jdbc.execute("ALTER TABLE tasks ADD CONSTRAINT batch_test_failure CHECK (title <> '第二条')");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> commands.createPendingAgentBatch(
                    1L, 1L, List.of(request("第一条"), request("第二条"))));
        } finally {
            jdbc.execute("ALTER TABLE tasks DROP CONSTRAINT batch_test_failure");
        }

        verify(tasks, times(2)).save(any(Task.class));
        verify(notifications, never()).enqueueTaskAssigned(any());
        assertEmptyBatch();
    }

    @Test
    void candidateCannotOverridePendingOrAgent() {
        for (String status : List.of("DRAFT", "IN_PROGRESS", "COMPLETED", "CANCELLED")) {
            CreateTaskRequest invalid = request("非法状态");
            invalid.setStatus(status);
            assertThrows(ApiException.class, () -> commands.createPendingAgentBatch(
                    1L, 1L, List.of(request("第一条"), invalid)));
        }
        CreateTaskRequest invalidSource = request("非法来源");
        invalidSource.setSource("MANUAL");
        assertThrows(ApiException.class, () -> commands.createPendingAgentBatch(
                1L, 1L, List.of(request("第一条"), invalidSource)));
        verify(tasks, never()).save(any(Task.class));
        assertEmptyBatch();
    }

    @Test
    void childDefaultsDoNotMutateCandidateInputs() {
        jdbc.update("UPDATE family_members SET role = 'CHILD' WHERE family_id = 1 AND user_id = 1");
        CreateTaskRequest first = request("自己的第一条");
        CreateTaskRequest second = request("自己的第二条");
        first.setAssigneeId(null);
        second.setAssigneeId(null);

        List<Task> created = commands.createPendingAgentBatch(1L, 1L, List.of(first, second));

        assertEquals(2, created.size());
        for (Task task : created) {
            assertEquals(TaskStatus.PENDING, task.getStatus());
            assertEquals(TaskSource.AGENT, task.getSource());
            assertEquals(1L, task.getAssigneeId());
            assertNull(task.getExpiresAt());
        }
        assertNull(first.getAssigneeId());
        assertNull(second.getAssigneeId());
        assertEquals(2, count("tasks"));
        assertEquals(2, count("task_notifications"));
    }

    @Test
    void dispatcherCannotSeeNotificationsBeforeOuterCommit() {
        doReturn("SENT").when(notifications).deliverTaskAssigned(any(Task.class));
        TransactionTemplate independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        new TransactionTemplate(manager).executeWithoutResult(status -> {
            List<Task> created = commands.createPendingAgentBatch(
                    1L, 1L, List.of(request("第一条"), request("第二条")));
            assertEquals(2, created.size());
            entityManager.flush();
            assertEquals(2, count("task_notifications"));
            independent.executeWithoutResult(reader -> {
                assertEmptyBatch();
                dispatcher.dispatchFamily(1L);
            });
            verify(notifications, never()).deliverTaskAssigned(any());
        });

        assertEquals(2, count("tasks"));
        assertEquals(2, count("task_notifications"));
        dispatcher.dispatchFamily(1L);
        verify(notifications, times(2)).deliverTaskAssigned(any());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_notifications WHERE family_id = 1 AND status = 'SENT'", Integer.class));
    }

    @Test
    void outerRollbackRemovesTasksAndNotificationQueue() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            commands.createPendingAgentBatch(1L, 1L, List.of(request("第一条"), request("第二条")));
            entityManager.flush();
            assertEquals(2, count("tasks"));
            assertEquals(2, count("task_notifications"));
            status.setRollbackOnly();
        });

        assertEmptyBatch();
        dispatcher.dispatchFamily(1L);
        verify(notifications, never()).deliverTaskAssigned(any());
    }

    private CreateTaskRequest request(String title) {
        CreateTaskRequest request = new CreateTaskRequest();
        request.setTitle(title);
        request.setAssigneeId(1L);
        return request;
    }

    private int count(String table) {
        // 表名仅由本测试固定常量传入，业务数据统计仍严格限定家庭。
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE family_id = 1", Integer.class);
    }

    private void assertEmptyBatch() {
        assertEquals(0, count("tasks"));
        assertEquals(0, count("task_notifications"));
    }
}
