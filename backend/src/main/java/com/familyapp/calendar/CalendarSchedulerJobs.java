package com.familyapp.calendar;

import com.familyapp.task.Task;
import com.familyapp.task.TaskPriority;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskSource;
import com.familyapp.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Change 13 日程调度器（Task 5）：每日 02:30 扫描启用日程，匹配今日重复规则，生成 PENDING 任务。
 *
 * <p>设计要点：
 * <ul>
 *   <li>逐家庭隔离（参照 {@code agent.TaskSchedulerJobs}）：单家庭异常不阻断其他家庭</li>
 *   <li>幂等靠 (source_event_id, due_date) 部分唯一索引 uq_tasks_event_date 兜底，
 *       冲突以 {@link DataAccessException} 捕获后跳过</li>
 *   <li>时钟经 {@link Clock} 注入（参照 {@code agent.RoutineMatcherService}），便于测试固定「今日」</li>
 *   <li>重复规则匹配委托 {@link CalendarRecurrenceMatcher}，与服务今日展开共用同一份逻辑</li>
 * </ul>
 */
@Component
public class CalendarSchedulerJobs {

    private static final Logger log = LoggerFactory.getLogger(CalendarSchedulerJobs.class);

    private final CalendarEventRepository eventRepository;
    private final TaskRepository taskRepository;
    private final CalendarRecurrenceMatcher matcher;
    private final Clock clock;

    public CalendarSchedulerJobs(CalendarEventRepository eventRepository,
                                 TaskRepository taskRepository,
                                 CalendarRecurrenceMatcher matcher,
                                 Clock clock) {
        this.eventRepository = eventRepository;
        this.taskRepository = taskRepository;
        this.matcher = matcher;
        this.clock = clock;
    }

    /** 每天 02:30：为所有有启用日程的家庭生成今日任务，逐家庭隔离。 */
    @Scheduled(cron = "0 30 2 * * ?")
    public void generateTodayTasks() {
        LocalDate today = LocalDate.now(clock);
        List<Long> familyIds = eventRepository.findDistinctFamilyIdsWithActiveEvents();
        log.info("日程调度开始，共 {} 个家庭", familyIds.size());
        for (Long familyId : familyIds) {
            try {
                executeForFamily(familyId, today);
            } catch (Exception e) {
                log.error("家庭[{}]日程调度失败，隔离继续：{}", familyId, e.getMessage());
            }
        }
    }

    /** 单家庭执行（可测试入口）：匹配今日启用的日程，逐条生成任务。 */
    public void executeForFamily(Long familyId, LocalDate today) {
        List<CalendarEvent> events = eventRepository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(familyId);
        for (CalendarEvent event : events) {
            if (!matcher.matchesToday(event, today)) {
                continue;
            }
            try {
                createTaskFromEvent(event, today);
            } catch (DataAccessException e) {
                // 唯一索引冲突 = 今日已生成过，幂等跳过
                log.debug("日程[{}]今日任务已存在，跳过", event.getId());
            }
        }
    }

    /** 由日程构造任务：拷贝家庭/标题/描述/时间点，标记来源 CALENDAR + sourceEventId 供幂等索引兜底。 */
    private void createTaskFromEvent(CalendarEvent event, LocalDate today) {
        Task task = new Task();
        task.setFamilyId(event.getFamilyId());
        task.setTitle(event.getTitle());
        task.setDescription(event.getDescription());
        task.setDueDate(today);
        task.setDueTime(event.getEventTime());
        task.setSourceEventId(event.getId());
        task.setSource(TaskSource.CALENDAR);
        task.setStatus(TaskStatus.PENDING);
        task.setPriority(TaskPriority.MEDIUM);
        task.setAssigneeProfileId(event.getAssigneeProfileId());
        task.setCategoryId(event.getCategoryId());
        task.setCreatedBy(event.getCreatedBy());
        taskRepository.save(task);
    }
}
