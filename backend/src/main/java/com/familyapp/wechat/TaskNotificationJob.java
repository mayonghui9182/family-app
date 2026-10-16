package com.familyapp.wechat;

import com.familyapp.family.FamilyRepository;
import com.familyapp.task.TaskRepository;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** 指派提醒独立投递，短事务抢占，网络调用在事务外。 */
@Component
public class TaskNotificationJob {
    private final TaskNotificationRepository repository;
    private final FamilyRepository families;
    private final TaskRepository tasks;
    private final WechatNotifyService notify;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    public TaskNotificationJob(TaskNotificationRepository repository, FamilyRepository families,
            TaskRepository tasks, WechatNotifyService notify, PlatformTransactionManager manager,
            Clock clock, @Value("${wechat.notify.enabled:true}") boolean enabled) {
        this.repository = repository; this.families = families; this.tasks = tasks;
        this.notify = notify; this.tx = new TransactionTemplate(manager); this.clock = clock; this.enabled = enabled;
    }
    @Scheduled(fixedDelayString = "${wechat.notifications.poll-ms:10000}", initialDelay = 60000)
    public void dispatch() {
        if (!enabled) return;
        for (Long familyId : families.findAllActiveIds()) dispatchFamily(familyId);
    }
    public void dispatchFamily(Long familyId) {
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        for (TaskNotification n : repository.pending(familyId, now, PageRequest.of(0, 20))) {
            var lease = now.plusSeconds(300);
            Integer claimed = tx.execute(s -> repository.claim(n.id, familyId, now, lease));
            if (claimed == null || claimed == 0) continue;
            String outcome;
            if (n.attempts >= 5) {
                outcome = "FAILED";
            } else {
                try {
                    outcome = tasks.findByIdAndFamilyId(n.taskId, familyId)
                            .map(notify::deliverTaskAssigned).orElse("SKIPPED");
                } catch (RuntimeException e) {
                    outcome = "PENDING";
                }
                if ("PENDING".equals(outcome) && n.attempts + 1 >= 5) outcome = "FAILED";
            }
            String finalStatus = outcome;
            var next = now.plusSeconds(30L * (1L << Math.min(n.attempts, 5)));
            tx.executeWithoutResult(s -> repository.finish(n.id, familyId, lease, finalStatus, next));
        }
    }
}
