package com.familyapp.agent;

import com.familyapp.task.TaskSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Change 2 调度器增量（规格 §5.7）：
 * 02:00 日常（逐家庭匹配 routine conditions 生成任务）；
 * 03:00 一次性（跨家庭先到先得原子消化 task_schedules）。
 *
 * <p>多实例互斥在 Change 7 引入分布式锁/Redis 前，按单实例部署假设运行；
 * 一次性调度依赖 task_schedules 的条件 UPDATE 行锁天然保证并发下唯一消化。</p>
 */
@Component
public class TaskSchedulerJobs {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerJobs.class);

    private final RuleTemplateRepository templateRepository;
    private final RoutineMatcherService routineMatcherService;
    private final PendingScheduleService pendingScheduleService;
    private final DirectTaskCreator directTaskCreator;

    public TaskSchedulerJobs(RuleTemplateRepository templateRepository,
                             RoutineMatcherService routineMatcherService,
                             PendingScheduleService pendingScheduleService,
                             DirectTaskCreator directTaskCreator) {
        this.templateRepository = templateRepository;
        this.routineMatcherService = routineMatcherService;
        this.pendingScheduleService = pendingScheduleService;
        this.directTaskCreator = directTaskCreator;
    }

    /** 每天 02:00 日常：按拥有启用 routine 模板的家庭逐家生成。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyRoutineJob() {
        List<Long> families = templateRepository.findDistinctFamilyIdsWithActiveRoutine();
        log.info("日常调度开始，共 {} 个家庭", families.size());
        for (Long familyId : families) {
            try {
                executeForFamily(familyId);
            } catch (Exception e) {
                log.error("家庭[{}]日常调度失败，隔离继续：{}", familyId, e.getMessage());
            }
        }
    }

    /** 单家庭日常执行（可测试入口）：匹配今日 routine 逐条生成任务。 */
    public void executeForFamily(Long familyId) {
        List<DueTodayRoutine> hits = routineMatcherService.dueToday(familyId);
        for (DueTodayRoutine hit : hits) {
            RuleTemplate template = templateRepository
                    .findByIdAndFamilyId(hit.templateId(), familyId).orElse(null);
            if (template == null) {
                continue;
            }
            JsonNode node = template.getContent();
            if (hit.needsAi()) {
                directTaskCreator.decomposeOrFallback(familyId, node, TaskSource.ROUTINE);
            } else {
                directTaskCreator.createFromTemplate(familyId, node, TaskSource.ROUTINE);
            }
        }
    }

    /** 每天 03:00 一次性定时：跨家庭先到先得原子消化，直至无待执行。 */
    @Scheduled(cron = "0 0 3 * * ?")
    public void oneTimeScheduleJob() {
        int processed = 0;
        while (processed < 500 && processNextOnce()) {
            processed++;
        }
        log.info("一次性调度本轮处理 {} 条", processed);
    }

    /** 原子领取并处理一条（可测试入口）。返回是否领取到。 */
    public boolean processNextOnce() {
        Optional<TaskSchedule> schedule = pendingScheduleService.claimNextGlobal();
        if (schedule.isEmpty()) {
            return false;
        }
        try {
            JsonNode node = schedule.get().getTargetTemplate();
            Long familyId = schedule.get().getFamilyId();
            if (AgentApiDtos.needsAi(node)) {
                directTaskCreator.decomposeOrFallback(familyId, node, TaskSource.SCHEDULE);
            } else {
                directTaskCreator.createFromTemplate(familyId, node, TaskSource.SCHEDULE);
            }
        } catch (Exception e) {
            log.error("调度[{}]处理失败，隔离继续：{}", schedule.get().getId(), e.getMessage());
        }
        return true;
    }
}
