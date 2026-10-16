package com.familyapp.agent;

import com.familyapp.task.TaskSource;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C-17 on-completed 事件触发服务。
 *
 * <p>查 source_task 的生效触发器逐条产出下游任务：
 * needs_ai=true → DirectTaskCreator.decomposeOrFallback（B-1 拆解或降级直建+中文提示便签）；
 * 否则 DirectTaskCreator.createFromTemplate 直建。同事务执行，上游回滚整条触发链回滚。</p>
 *
 * <p>同一触发器 5 分钟内的重复完成事件内存去重，防上游重复回调造成重复生成。</p>
 */
@Service
public class TaskEventService {

    private static final Logger log = LoggerFactory.getLogger(TaskEventService.class);
    private static final long DEDUP_WINDOW_MS = 5 * 60 * 1000;

    private final TaskTriggerRepository triggerRepository;
    private final DirectTaskCreator directTaskCreator;
    private final Map<Long, Instant> lastFiredAt = new ConcurrentHashMap<>();

    public TaskEventService(TaskTriggerRepository triggerRepository,
                            DirectTaskCreator directTaskCreator) {
        this.triggerRepository = triggerRepository;
        this.directTaskCreator = directTaskCreator;
    }

    /** 处理源任务完成事件，返回本轮实际触发的下游产出数。 */
    @Transactional
    public int onCompleted(Long sourceTaskId, Long familyId) {
        List<TaskTrigger> triggers = triggerRepository.findBySourceTaskIdAndIsActiveTrue(sourceTaskId);
        int count = 0;
        for (TaskTrigger trigger : triggers) {
            long scopeKey = trigger.getId() != null
                    ? trigger.getId()
                    : System.identityHashCode(trigger);
            if (recentlyFired(scopeKey)) {
                log.info("触发器[{}]5 分钟内已触发过，本次去重跳过", scopeKey);
                continue;
            }
            markFired(scopeKey);

            JsonNode node = trigger.getTargetTemplate();
            if (AgentApiDtos.needsAi(node)) {
                directTaskCreator.decomposeOrFallback(trigger.getFamilyId(), node, TaskSource.TRIGGER);
            } else {
                directTaskCreator.createFromTemplate(trigger.getFamilyId(), node, TaskSource.TRIGGER);
            }
            count++;
        }
        return count;
    }

    private boolean recentlyFired(long key) {
        Instant last = lastFiredAt.get(key);
        return last != null && Duration.between(last, Instant.now()).toMillis() < DEDUP_WINDOW_MS;
    }

    private void markFired(long key) {
        lastFiredAt.put(key, Instant.now());
    }
}
