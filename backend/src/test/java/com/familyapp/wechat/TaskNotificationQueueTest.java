package com.familyapp.wechat;

import com.familyapp.task.Task;
import com.familyapp.task.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("classpath:db/v6-test-setup.sql")
class TaskNotificationQueueTest {
    @Autowired TaskNotificationQueue queue;
    @Autowired TaskNotificationRepository repository;
    @Autowired TaskNotificationJob job;
    @Autowired TaskRepository tasks;
    @Autowired EntityManager em;
    @MockBean WechatNotifyService notify;

    private Task create() {
        Task task = new Task(); task.setFamilyId(1L); task.setTitle("提醒验证"); task.setAssigneeId(1L);
        return tasks.saveAndFlush(task);
    }
    @Test void 入队不发送且事务回滚不留下提醒() {
        long before = repository.count();
        queue.enqueue(create());
        assertThat(repository.count()).isEqualTo(before + 1);
        verifyNoInteractions(notify);
        TestTransaction.flagForRollback(); TestTransaction.end();
        assertThat(repository.count()).isEqualTo(before);
    }
    @Test void 发送失败退避重试成功后不重复发送且隔离家庭() {
        queue.enqueue(create()); em.flush();
        when(notify.deliverTaskAssigned(any())).thenReturn("PENDING", "SENT");
        job.dispatchFamily(999L); verifyNoInteractions(notify);
        job.dispatchFamily(1L); em.clear();
        TaskNotification n = repository.findAll().get(0);
        assertThat(n.status).isEqualTo("PENDING");
        assertThat(n.attempts).isEqualTo(1);
        job.dispatchFamily(1L); verify(notify, times(1)).deliverTaskAssigned(any());
        n.availableAt = Instant.now().minusSeconds(1); repository.saveAndFlush(n); em.clear();
        job.dispatchFamily(1L); em.clear();
        assertThat(repository.findById(n.id).orElseThrow().status).isEqualTo("SENT");
        job.dispatchFamily(1L); verify(notify, times(2)).deliverTaskAssigned(any());
    }
}
