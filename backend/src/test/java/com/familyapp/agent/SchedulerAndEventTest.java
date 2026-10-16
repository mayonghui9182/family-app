package com.familyapp.agent;

import com.familyapp.task.Task;
import com.familyapp.task.TaskPriority;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskService;
import com.familyapp.task.TaskSource;
import com.familyapp.task.TaskStatus;
import com.familyapp.task.dto.UpdateTaskRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Change 2 Task 3：调度器与完成事件挂钩测试（手动调用 TaskSchedulerJobs 方法，不依赖 cron 时间）。
 * 用例1 daily 日常生成 / 用例2 一次性消化 / 用例3 并发幂等 / 用例4 changeStatus 完成边沿触发。
 */
@SpringBootTest
@ActiveProfiles("internal-api-test")
@TestPropertySource(properties = "internal-api.secret=test-secret-correct")
@Transactional
class SchedulerAndEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long FID = 9201L;

    @Autowired TaskSchedulerJobs jobs;
    @Autowired TaskRepository taskRepository;
    @Autowired RuleTemplateRepository templateRepository;
    @Autowired TaskScheduleRepository scheduleRepository;
    @Autowired TaskTriggerRepository triggerRepository;
    @Autowired TaskService taskService;

    private RuleTemplate routine(String contentJson) {
        try {
            int today = LocalDate.now().getDayOfWeek().getValue();
            RuleTemplate t = new RuleTemplate();
            t.setFamilyId(FID); t.setType("routine"); t.setName("今日日常");
            t.setConditions(MAPPER.readTree("{\"kind\":\"weekly\",\"weekdays\":[" + today + "]}"));
            t.setContent(MAPPER.readTree(contentJson));
            t.setIsActive(true);
            return templateRepository.save(t);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 用例1：dailyRoutineJob.executeForFamily → 生成 PENDING / source=ROUTINE 任务。 */
    @Test
    void daily_job_createsRoutineTask() {
        routine("{\"title\":\"浇花\",\"priority\":\"LOW\",\"needs_ai\":false}");

        jobs.executeForFamily(FID);

        List<Task> created = taskRepository.findByFamilyIdAndDeletedAtIsNull(FID).stream()
                .filter(t -> "浇花".equals(t.getTitle())).toList();
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getStatus()).isEqualTo(TaskStatus.PENDING);
        assertThat(created.get(0).getSource()).isEqualTo(TaskSource.ROUTINE);
    }

    /** 用例2：oneTimeScheduleJob → 调度置为已执行 + 生成 source=SCHEDULE 任务。 */
    @Test
    void onetime_job_consumesScheduleAndCreatesTask() throws Exception {
        TaskSchedule sch = new TaskSchedule();
        sch.setFamilyId(FID); sch.setTriggerDate(LocalDate.now());
        sch.setTargetTemplate(MAPPER.readTree("{\"title\":\"预约牙医\",\"needs_ai\":false}"));
        sch = scheduleRepository.save(sch);

        jobs.oneTimeScheduleJob();

        assertThat(scheduleRepository.findById(sch.getId()).orElseThrow().getIsExecuted()).isTrue();
        List<Task> created = taskRepository.findByFamilyIdAndDeletedAtIsNull(FID).stream()
                .filter(t -> "预约牙医".equals(t.getTitle())).toList();
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getSource()).isEqualTo(TaskSource.SCHEDULE);
    }

    /** 用例3：10 并发消费同一调度 → 恰有一条被消化（条件 UPDATE 行锁幂等）。 */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void onetime_job_concurrentExactlyOne() throws Exception {
        TaskSchedule sch = new TaskSchedule();
        sch.setFamilyId(FID); sch.setTriggerDate(LocalDate.now());
        sch.setTargetTemplate(MAPPER.readTree("{\"title\":\"并发目标\",\"needs_ai\":false}"));
        scheduleRepository.save(sch);

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit((Callable<Boolean>) () -> {
                ready.countDown();
                go.await();
                return jobs.processNextOnce();
            }));
        }
        ready.await();
        go.countDown();
        int consumed = 0;
        for (Future<Boolean> f : futures) {
            if (f.get()) {
                consumed++;
            }
        }
        pool.shutdown();
        assertThat(consumed).as("并发下恰一条被消化").isEqualTo(1);

        assertThat(taskRepository.findByFamilyIdAndDeletedAtIsNull(FID).stream()
                .filter(t -> "并发目标".equals(t.getTitle())).count()).isEqualTo(1);
    }

    /** 用例4：changeStatus → COMPLETED 边沿触发下游（有生效触发器）。 */
    @Test
    void changeStatus_completed_edge_triggersDownstream() throws Exception {
        Task src = new Task();
        src.setFamilyId(FID); src.setTitle("源任务");
        src.setStatus(TaskStatus.PENDING); src.setPriority(TaskPriority.MEDIUM);
        src.setSource(TaskSource.MANUAL);
        src = taskRepository.save(src);

        TaskTrigger trig = new TaskTrigger();
        trig.setFamilyId(FID); trig.setSourceTaskId(src.getId());
        trig.setTargetTemplate(MAPPER.readTree(
                "{\"title\":\"下游直建\",\"needs_ai\":false,\"priority\":\"HIGH\"}"));
        trig.setIsActive(true);
        triggerRepository.save(trig);

        UpdateTaskRequest req = new UpdateTaskRequest();
        req.setStatus("COMPLETED");
        com.familyapp.auth.UserContext.setCurrent(new com.familyapp.auth.UserContext.CurrentUser(
                1L, "test@test.com", "测试成员", FID, "OWNER"));
        try {
            taskService.update(src.getId(), req);
        } finally {
            com.familyapp.auth.UserContext.clear();
        }

        assertThat(taskRepository.findByFamilyIdAndDeletedAtIsNull(FID).stream()
                .anyMatch(t -> "下游直建".equals(t.getTitle())
                        && t.getPriority() == TaskPriority.HIGH
                        && t.getSource() == TaskSource.TRIGGER)).isTrue();
    }
}
