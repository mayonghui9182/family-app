package com.familyapp.wechat;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.task.Task;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskStatus;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信模板消息通知服务（Change 12，design D5/D6；Change 13 增日程早报）。
 *
 * <p>负责「什么事件、发给谁、什么参数」：接收人 = 家庭未退出成员中已绑定 openid 者。
 * 四个公开入口（任务早报/总结完成/任务指派/日程早报）全部吞异常不抛出，保证推送旁路不影响业务。
 */
@Service
public class WechatNotifyService {

    private static final Logger log = LoggerFactory.getLogger(WechatNotifyService.class);

    /** 早报统计口径：完成与取消的任务不计入到期/逾期。 */
    private static final List<TaskStatus> DONE_STATUSES = List.of(TaskStatus.COMPLETED, TaskStatus.CANCELLED);

    private final WechatBindRepository bindRepository;
    private final FamilyMemberRepository memberRepository;
    private final FamilyRepository familyRepository;
    private final TaskRepository taskRepository;
    private final WechatNotifyClient notifyClient;
    /** 注入时钟（复用 ClockConfig 全局 Bean）：业务取「今天」必须可固定，杜绝测试硬编码日历。 */
    private final Clock clock;
    private final TaskNotificationQueue notificationQueue;
    private final com.familyapp.profile.PersonProfileRepository profileRepository;

    public WechatNotifyService(WechatBindRepository bindRepository,
                               FamilyMemberRepository memberRepository,
                               FamilyRepository familyRepository,
                               TaskRepository taskRepository,
                               WechatNotifyClient notifyClient,
                               Clock clock,
                               com.familyapp.profile.PersonProfileRepository profileRepository, TaskNotificationQueue notificationQueue) {
        this.bindRepository = bindRepository;
        this.memberRepository = memberRepository;
        this.familyRepository = familyRepository;
        this.taskRepository = taskRepository;
        this.notifyClient = notifyClient;
        this.clock = clock;
        this.profileRepository = profileRepository;
        this.notificationQueue = notificationQueue;
    }

    /** 早报推送结果（job 日志/测试可观测）。 */
    public record DigestResult(int recipients, long todayDue, long overdue, boolean sent) {
    }

    /**
     * 每日早报：统计今日到期/逾期，双零不打扰，推给家庭已绑定成员。任何异常吞掉。
     */
    public void notifyDigest(Long familyId, LocalDate today) {
        try {
            digestForFamily(familyId, today);
        } catch (Exception e) {
            log.warn("家庭[{}]早报推送失败（已隔离，不影响业务）：{}", familyId, e.getMessage());
        }
    }

    /** 单家庭早报（可测试入口，不吞异常；job 层逐家隔离）。 */
    public DigestResult digestForFamily(Long familyId, LocalDate today) {
        long todayDue = taskRepository
                .countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDate(familyId, DONE_STATUSES, today);
        long overdue = taskRepository
                .countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDateBefore(familyId, DONE_STATUSES, today);
        if (todayDue == 0 && overdue == 0) {
            return new DigestResult(0, 0, 0, false);
        }
        List<String> openids = resolveOpenids(familyId);
        if (openids.isEmpty()) {
            return new DigestResult(0, todayDue, overdue, false);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("today_due", todayDue);
        params.put("overdue", overdue);
        notifyClient.sendMessages(buildMessages(openids, "task_digest", params));
        return new DigestResult(openids.size(), todayDue, overdue, true);
    }

    /**
     * 每日总结生成成功后通知（C-20 finish success 钩子调用）。任何异常吞掉。
     */
    public void notifySummaryReady(Long familyId) {
        try {
            List<String> openids = resolveOpenids(familyId);
            if (openids.isEmpty()) {
                return;
            }
            String familyName = familyRepository.findById(familyId)
                    .map(Family::getName).orElse("我的家庭");
            Map<String, Object> params = new HashMap<>();
            params.put("family_name", familyName);
            params.put("date", LocalDate.now(clock).toString());
            notifyClient.sendMessages(buildMessages(openids, "summary_ready", params));
        } catch (Exception e) {
            log.warn("家庭[{}]总结完成推送失败（已隔离）：{}", familyId, e.getMessage());
        }
    }

    /**
     * 任务创建时按有效档案关联账号通知；无档案的历史任务兼容旧账号字段。
     */
    public void enqueueTaskAssigned(Task task) { notificationQueue.enqueue(task); }

    public void notifyTaskAssigned(Task task) { deliverTaskAssigned(task); }

    /** 返回可观测投递结果；只有微信逐条确认成功才记为已发送。 */
    public String deliverTaskAssigned(Task task) {
        if (task == null || task.getFamilyId() == null) return "SKIPPED";
        if (task.getStatus() != com.familyapp.task.TaskStatus.PENDING
                && task.getStatus() != com.familyapp.task.TaskStatus.IN_PROGRESS) return "SKIPPED";
        try {
            Long userId = task.getAssigneeId();
            if (task.getAssigneeProfileId() != null) {
                userId = profileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(
                        task.getFamilyId(), task.getAssigneeProfileId())
                        .map(com.familyapp.profile.PersonProfile::getUserId).orElse(null);
            }
            if (userId == null || memberRepository.findByFamilyIdAndUserIdAndLeftAtIsNull(
                    task.getFamilyId(), userId).isEmpty()) return "SKIPPED";
            WechatBind bind = bindRepository.findByUserId(userId).orElse(null);
            if (bind == null) {
                return "SKIPPED";
            }
            Map<String, Object> params = new HashMap<>();
            params.put("title", task.getTitle());
            params.put("due_date", task.getDueDate() != null ? task.getDueDate().toString() : null);
            var results = notifyClient.sendMessages(List.of(
                    new WechatNotifyMessage(bind.getOpenid(), "task_assigned", params)));
            if (results == null || results.isEmpty()) return "PENDING";
            var result = results.get(0);
            return result.ok() ? "SENT" : Boolean.TRUE.equals(result.skipped()) ? "SKIPPED" : "PENDING";
        } catch (Exception e) {
            log.warn("任务[{}]指派推送失败（已隔离）：{}", task.getId(), e.getMessage());
        }
        return "PENDING";
    }

    /**
     * Change 13: 日程早报推送。接收人由调用方决定（执行人或全员）。
     */
    public void notifyEventDigest(List<String> openids, int eventCount, String eventSummary) {
        if (openids.isEmpty() || eventCount == 0) return;
        try {
            Map<String, Object> params = new HashMap<>();
            params.put("event_count", eventCount);
            params.put("event_summary", eventSummary);
            notifyClient.sendMessages(buildMessages(openids, "event_digest", params));
        } catch (Exception e) {
            log.warn("日程早报推送失败（已隔离）：{}", e.getMessage());
        }
    }

    /** 解析家庭未退出成员中已绑定微信的 openid 列表。 */
    private List<String> resolveOpenids(Long familyId) {
        List<Long> userIds = memberRepository.findAllByFamilyIdAndLeftAtIsNull(familyId)
                .stream().map(FamilyMember::getUserId).toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return bindRepository.findAllByUserIdIn(userIds).stream()
                .map(WechatBind::getOpenid).toList();
    }

    private static List<WechatNotifyMessage> buildMessages(
            List<String> openids, String event, Map<String, Object> params) {
        return openids.stream().map(openid -> new WechatNotifyMessage(openid, event, params)).toList();
    }
}
