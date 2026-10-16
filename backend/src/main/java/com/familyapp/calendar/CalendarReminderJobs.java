package com.familyapp.calendar;

import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.wechat.WechatBind;
import com.familyapp.wechat.WechatBindRepository;
import com.familyapp.wechat.WechatNotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Change 13 日程早报（Task 6）：每日 07:30 汇总今日提醒日程，经微信模板消息推送。
 *
 * <p>设计要点：
 * <ul>
 *   <li>逐家庭隔离（参照 {@code wechat.WechatNotifyJobs} 与 {@link CalendarSchedulerJobs}）：
 *       单家庭异常只记日志，不阻断其他家庭</li>
 *   <li>接收人口径：日程有执行人档案 → 只推给该档案绑定的用户；无执行人 → 推家庭全员已绑定者；
 *       两者混在同一天时取并集去重（对齐 Change 12「未绑定不发」，执行人未绑定也不回退全家）</li>
 *   <li>全员绑定成员查询惰性执行：仅当存在无执行人日程时才发起，避免无谓的 DB 往返</li>
 *   <li>档案/成员查询一律带 family_id 作用域（硬约束 4 多租户隔离）</li>
 *   <li>摘要按时间点升序、最多 5 条后以「等」截断，精确件数由 {@code event_count} 承载（老人友好）</li>
 *   <li>时钟经 {@link Clock} 注入（沿用 {@link CalendarSchedulerJobs} 约定），便于测试固定「今日」</li>
 *   <li>重复规则匹配复用 {@link CalendarRecurrenceMatcher}，与调度器/今日展开同一份逻辑</li>
 * </ul>
 */
@Component
public class CalendarReminderJobs {

    private static final Logger log = LoggerFactory.getLogger(CalendarReminderJobs.class);

    /** 摘要最多展示的日程条数，超出部分以「等」结尾。 */
    private static final int MAX_SUMMARY_ITEMS = 5;

    private final CalendarEventRepository eventRepository;
    private final WechatNotifyService notifyService;
    private final WechatBindRepository bindRepository;
    private final FamilyMemberRepository memberRepository;
    private final PersonProfileRepository profileRepository;
    private final CalendarRecurrenceMatcher matcher;
    private final Clock clock;

    public CalendarReminderJobs(CalendarEventRepository eventRepository,
                               WechatNotifyService notifyService,
                               WechatBindRepository bindRepository,
                               FamilyMemberRepository memberRepository,
                               PersonProfileRepository profileRepository,
                               CalendarRecurrenceMatcher matcher,
                               Clock clock) {
        this.eventRepository = eventRepository;
        this.notifyService = notifyService;
        this.bindRepository = bindRepository;
        this.memberRepository = memberRepository;
        this.profileRepository = profileRepository;
        this.matcher = matcher;
        this.clock = clock;
    }

    /** 每天 07:30：为所有有启用日程的家庭推送今日日程早报，逐家庭隔离。 */
    @Scheduled(cron = "0 30 7 * * ?")
    public void sendDailyEventDigest() {
        LocalDate today = LocalDate.now(clock);
        List<Long> familyIds = eventRepository.findDistinctFamilyIdsWithActiveEvents();
        log.info("日程早报开始，共 {} 个家庭", familyIds.size());
        for (Long familyId : familyIds) {
            try {
                executeForFamily(familyId, today);
            } catch (Exception e) {
                log.error("家庭[{}]日程早报失败，隔离继续：{}", familyId, e.getMessage());
            }
        }
    }

    /** 单家庭执行（可测试入口）：匹配今日提醒日程 → 组装摘要 → 解析接收人 → 推送。 */
    public void executeForFamily(Long familyId, LocalDate today) {
        List<CalendarEvent> events = eventRepository
                .findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(familyId)
                .stream()
                .filter(event -> Boolean.TRUE.equals(event.getReminderEnabled()))
                .filter(event -> matcher.matchesToday(event, today))
                .sorted(Comparator.comparing(CalendarEvent::getEventTime,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (events.isEmpty()) {
            return;
        }

        Set<String> openids = new LinkedHashSet<>();
        List<String> familyBoundOpenids = null;
        for (CalendarEvent event : events) {
            if (event.getAssigneeProfileId() != null) {
                String openid = resolveAssigneeOpenid(familyId, event.getAssigneeProfileId());
                if (openid != null) {
                    openids.add(openid);
                }
                continue;
            }
            if (familyBoundOpenids == null) {
                familyBoundOpenids = resolveFamilyBoundOpenids(familyId);
            }
            openids.addAll(familyBoundOpenids);
        }
        if (openids.isEmpty()) {
            log.debug("家庭[{}]无可推送对象（未绑定微信），跳过日程早报", familyId);
            return;
        }
        notifyService.notifyEventDigest(new ArrayList<>(openids), events.size(), buildSummary(events));
    }

    /** 执行人档案 → 绑定用户 → openid；档案缺失/无 user_id（如宠物）/未绑定均返回 null。 */
    private String resolveAssigneeOpenid(Long familyId, Long profileId) {
        PersonProfile profile = profileRepository
                .findByFamilyIdAndIdAndDeletedAtIsNull(familyId, profileId).orElse(null);
        if (profile == null || profile.getUserId() == null) {
            return null;
        }
        WechatBind bind = bindRepository.findByUserId(profile.getUserId()).orElse(null);
        return bind == null ? null : bind.getOpenid();
    }

    /** 家庭未退出成员中已绑定微信的 openid 列表（与 Change 12 早报同一口径）。 */
    private List<String> resolveFamilyBoundOpenids(Long familyId) {
        List<Long> userIds = memberRepository.findAllByFamilyIdAndLeftAtIsNull(familyId)
                .stream().map(FamilyMember::getUserId).toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        return bindRepository.findAllByUserIdIn(userIds).stream()
                .map(WechatBind::getOpenid).toList();
    }

    /** 摘要形如「08:30 吃降压药、09:00 接送孙子」，超过 5 条以「等」截断。 */
    private String buildSummary(List<CalendarEvent> events) {
        List<String> items = events.stream().map(CalendarReminderJobs::formatLine).toList();
        if (items.size() <= MAX_SUMMARY_ITEMS) {
            return String.join("、", items);
        }
        return String.join("、", items.subList(0, MAX_SUMMARY_ITEMS)) + "等";
    }

    /** 单行：有时间点则「HH:mm 标题」，无时间点（全天日程）则只留标题。 */
    private static String formatLine(CalendarEvent event) {
        String time = event.getEventTime();
        if (time == null || time.isBlank()) {
            // 该无时间分支在当前 V11 约束下生产不可达：calendar_events.event_time 为 NOT NULL
            // 且带 HH:mm 正则 CHECK，创建/更新请求另有 @NotBlank 兜底。保留只为未来放宽
            // 「全天日程」时不致拼出前导空格。勿据其虚构测试 fixture（摘要类用例须用带 HH:MM 的真实形态）。
            return event.getTitle();
        }
        return time + " " + event.getTitle();
    }
}
