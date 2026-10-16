package com.familyapp.calendar;

import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.task.TaskRepository;
import com.familyapp.user.User;
import com.familyapp.wechat.WechatBind;
import com.familyapp.wechat.WechatBindRepository;
import com.familyapp.wechat.WechatNotifyClient;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyItemResult;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyMessage;
import com.familyapp.wechat.WechatNotifyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Change 13 Task 6：CalendarReminderJobs 纯 Mockito 单元测试（不起 Spring 上下文）。
 *
 * <p>覆盖简报 Step 1 的 5 项：
 * <ol>
 *   <li>有提醒日程 + 有执行人 + 执行人已绑定微信 → 推给执行人</li>
 *   <li>有提醒日程 + 无执行人 → 推给家庭全员已绑定成员</li>
 *   <li>无提醒日程 → 不推送</li>
 *   <li>模板 ID 留空 → 静默跳过（wechat-service 回 skipped，后端不抛不阻断）</li>
 *   <li>异常隔离：单家庭失败不阻断</li>
 * </ol>
 *
 * <p>另加口径钉死用例：提醒开关关闭不推、执行人未绑定不推、宠物档案（无 user_id）不推、
 * 混合日程接收人并集去重、摘要按时间点排序并截断、每日入口经注入时钟取「今日」。
 *
 * <p>时钟经 {@code Clock.fixed} 注入（沿用 CalendarSchedulerJobsTest 约定），「今日」为常量
 * {@link #TODAY}；命中今日的日程统一用 {@code {"freq":"daily"}} 构造，避免时间依赖型断言随日期漂移。
 */
class CalendarReminderJobsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定「今天」：2026-09-15，与 CalendarEventServiceTest / CalendarSchedulerJobsTest 同口径 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final long FAMILY_ID = 10L;

    private CalendarEventRepository eventRepository;
    private WechatNotifyService notifyService;
    private WechatBindRepository bindRepository;
    private FamilyMemberRepository memberRepository;
    private PersonProfileRepository profileRepository;
    private CalendarReminderJobs jobs;

    @BeforeEach
    void setUp() {
        eventRepository = mock(CalendarEventRepository.class);
        notifyService = mock(WechatNotifyService.class);
        bindRepository = mock(WechatBindRepository.class);
        memberRepository = mock(FamilyMemberRepository.class);
        profileRepository = mock(PersonProfileRepository.class);
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        jobs = new CalendarReminderJobs(eventRepository, notifyService, bindRepository,
                memberRepository, profileRepository, new CalendarRecurrenceMatcher(), clock);
    }

    /** 构造「每天重复」的提醒日程夹具（保证任意日期均命中）。 */
    private CalendarEvent dailyEvent(long id, String eventTime, String title, Long assigneeProfileId) {
        return event(id, eventTime, title, assigneeProfileId, "{\"freq\":\"daily\"}", true);
    }

    /** 构造一次性日程夹具（recurrence 为 null → 仅 eventDate 命中）。 */
    private CalendarEvent oneTimeEvent(long id, String eventTime, String title, LocalDate eventDate) {
        CalendarEvent e = event(id, eventTime, title, null, null, true);
        e.setEventDate(eventDate);
        return e;
    }

    /** recurrenceJson 为 null 表示一次性事件（日期默认取固定「今日」）。 */
    private CalendarEvent event(long id, String eventTime, String title, Long assigneeProfileId,
                               String recurrenceJson, boolean reminderEnabled) {
        CalendarEvent e = new CalendarEvent();
        e.setId(id);
        e.setFamilyId(FAMILY_ID);
        e.setTitle(title);
        e.setEventTime(eventTime);
        if (recurrenceJson == null) {
            e.setEventDate(TODAY);
        } else {
            try {
                e.setRecurrence(MAPPER.readTree(recurrenceJson));
            } catch (Exception ex) {
                throw new IllegalStateException("测试夹具 JSON 非法", ex);
            }
        }
        e.setIsActive(true);
        e.setReminderEnabled(reminderEnabled);
        e.setAssigneeProfileId(assigneeProfileId);
        return e;
    }

    private void stubActiveEvents(CalendarEvent... events) {
        when(eventRepository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(FAMILY_ID))
                .thenReturn(List.of(events));
    }

    private FamilyMember member(long userId) {
        FamilyMember m = new FamilyMember();
        m.setUserId(userId);
        return m;
    }

    private WechatBind bind(long userId, String openid) {
        User u = new User();
        u.setId(userId);
        WechatBind b = new WechatBind();
        b.setOpenid(openid);
        b.setUser(u);
        return b;
    }

    private PersonProfile profile(long profileId, Long userId) {
        PersonProfile p = new PersonProfile();
        p.setId(profileId);
        p.setFamilyId(FAMILY_ID);
        p.setUserId(userId);
        p.setName("档案" + profileId);
        return p;
    }

    /** 家庭全员（未退出成员）中有微信绑定者。 */
    private void stubFamilyBoundMembers(String... openidsWithUser) {
        // openidsWithUser 形如 "1:o1"，即 userId:openid
        List<FamilyMember> members = new ArrayList<>();
        List<WechatBind> binds = new ArrayList<>();
        for (String pair : openidsWithUser) {
            String[] parts = pair.split(":");
            long userId = Long.parseLong(parts[0]);
            members.add(member(userId));
            binds.add(bind(userId, parts[1]));
        }
        when(memberRepository.findAllByFamilyIdAndLeftAtIsNull(FAMILY_ID)).thenReturn(members);
        when(bindRepository.findAllByUserIdIn(anyCollection())).thenReturn(binds);
    }

    /** 执行人档案 → 绑定用户 → openid。 */
    private void stubAssignee(long profileId, Long userId, String openid) {
        when(profileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(FAMILY_ID, profileId))
                .thenReturn(Optional.of(profile(profileId, userId)));
        when(bindRepository.findByUserId(userId)).thenReturn(Optional.of(bind(userId, openid)));
    }

    // 用例 1：有提醒日程 + 有执行人 + 执行人已绑定 → 只推给执行人，且档案查询带 family_id 作用域
    @Test
    void assignee_with_wechat_bind_is_the_only_recipient() {
        stubActiveEvents(dailyEvent(100L, "08:30", "吃降压药", 7L));
        stubAssignee(7L, 3L, "o3");

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(notifyService).notifyEventDigest(List.of("o3"), 1, "08:30 吃降压药");
        verify(profileRepository).findByFamilyIdAndIdAndDeletedAtIsNull(FAMILY_ID, 7L);
        verify(memberRepository, never()).findAllByFamilyIdAndLeftAtIsNull(anyLong());
    }

    // 用例 2：无执行人 → 推给家庭全员已绑定成员
    @Test
    void event_without_assignee_goes_to_all_bound_family_members() {
        stubActiveEvents(dailyEvent(101L, "09:00", "孙子放学接送", null));
        stubFamilyBoundMembers("1:o1", "2:o2", "3:o3");
        // 成员 3 未绑定：仓储口径（findAllByUserIdIn）已过滤，只返回两条
        when(bindRepository.findAllByUserIdIn(anyCollection()))
                .thenReturn(List.of(bind(1L, "o1"), bind(2L, "o2")));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(notifyService).notifyEventDigest(List.of("o1", "o2"), 1, "09:00 孙子放学接送");
    }

    // 用例 3：今日无匹配日程 → 不推送、不查接收人
    @Test
    void no_event_today_sends_nothing() {
        CalendarEvent tomorrow = event(102L, "08:00", "明天的事", null, null, true);
        tomorrow.setEventDate(TODAY.plusDays(1));
        stubActiveEvents(tomorrow);

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verifyNoInteractions(notifyService);
        verifyNoInteractions(memberRepository);
        verifyNoInteractions(bindRepository);
    }

    // 补充口径：提醒开关关闭的日程不参与早报
    @Test
    void reminder_disabled_event_is_excluded() {
        stubActiveEvents(event(103L, "08:00", "静默日程", null, "{\"freq\":\"daily\"}", false));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verifyNoInteractions(notifyService);
    }

    // 补充口径：执行人未绑定微信 → 不推送（对齐 Change 12「未绑定不发」）
    @Test
    void assignee_without_wechat_bind_sends_nothing() {
        stubActiveEvents(dailyEvent(104L, "08:00", "测血糖", 7L));
        when(profileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(FAMILY_ID, 7L))
                .thenReturn(Optional.of(profile(7L, 3L)));
        when(bindRepository.findByUserId(3L)).thenReturn(Optional.empty());

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verifyNoInteractions(notifyService);
    }

    // 补充口径：宠物等无 user_id 的档案不做绑定查询、不推送
    @Test
    void assignee_profile_without_user_id_is_skipped() {
        stubActiveEvents(dailyEvent(105L, "08:00", "带狗打疫苗", 8L));
        when(profileRepository.findByFamilyIdAndIdAndDeletedAtIsNull(FAMILY_ID, 8L))
                .thenReturn(Optional.of(profile(8L, null)));

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(bindRepository, never()).findByUserId(any());
        verifyNoInteractions(notifyService);
    }

    // 补充口径：混合日程 → 接收人取并集且去重（执行人同时也是家庭成员时不重复发）
    @Test
    void mixed_recipients_are_unioned_and_deduplicated() {
        stubActiveEvents(dailyEvent(106L, "09:00", "买菜", 7L),
                dailyEvent(107L, "08:00", "家庭体检", null));
        stubAssignee(7L, 1L, "o1");
        stubFamilyBoundMembers("1:o1", "2:o2");

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(notifyService).notifyEventDigest(List.of("o1", "o2"), 2, "08:00 家庭体检、09:00 买菜");
    }

    // 补充口径：摘要按时间点升序，超过 5 条截断为「等」（件数由 event_count 承载）
    @Test
    void summary_is_time_ordered_and_capped() {
        stubActiveEvents(
                dailyEvent(108L, "11:00", "事项5", null),
                dailyEvent(109L, "07:00", "事项1", null),
                dailyEvent(110L, "15:00", "事项7", null),
                dailyEvent(111L, "09:00", "事项3", null),
                dailyEvent(112L, "08:00", "事项2", null),
                dailyEvent(113L, "13:00", "事项6", null),
                dailyEvent(114L, "10:00", "事项4", null));
        stubFamilyBoundMembers("1:o1");

        jobs.executeForFamily(FAMILY_ID, TODAY);

        verify(notifyService).notifyEventDigest(List.of("o1"), 7,
                "07:00 事项1、08:00 事项2、09:00 事项3、10:00 事项4、11:00 事项5等");
    }

    // 用例 4：模板 ID 留空 → wechat-service 回 skipped，后端静默跳过、不抛异常
    @Test
    void template_not_configured_is_skipped_silently() {
        WechatNotifyClient client = mock(WechatNotifyClient.class);
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        WechatNotifyService realService = new WechatNotifyService(bindRepository, memberRepository,
                mock(FamilyRepository.class), mock(TaskRepository.class), client, clock,
                mock(com.familyapp.profile.PersonProfileRepository.class),
                mock(com.familyapp.wechat.TaskNotificationQueue.class));
        CalendarReminderJobs realJobs = new CalendarReminderJobs(eventRepository, realService,
                bindRepository, memberRepository, profileRepository, new CalendarRecurrenceMatcher(), clock);
        stubActiveEvents(dailyEvent(115L, "08:30", "吃药", null));
        stubFamilyBoundMembers("1:o1");
        when(client.sendMessages(any())).thenReturn(List.of(
                new WechatNotifyItemResult("o1", false, true, null, "template_not_configured")));

        assertThatCode(() -> realJobs.executeForFamily(FAMILY_ID, TODAY)).doesNotThrowAnyException();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WechatNotifyMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(client, times(1)).sendMessages(captor.capture());
        WechatNotifyMessage message = captor.getValue().get(0);
        assertThat(message.event()).isEqualTo("event_digest");
        assertThat(message.openid()).isEqualTo("o1");
        assertThat(message.params()).containsEntry("event_count", 1)
                .containsEntry("event_summary", "08:30 吃药");
    }

    // 用例 5：单家庭异常不阻断其他家庭
    @Test
    void one_family_failure_does_not_block_others() {
        when(eventRepository.findDistinctFamilyIdsWithActiveEvents()).thenReturn(List.of(1L, 2L));
        when(eventRepository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(1L))
                .thenThrow(new RuntimeException("db down"));
        CalendarEvent family2Event = dailyEvent(200L, "08:00", "复诊", null);
        family2Event.setFamilyId(2L);
        when(eventRepository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(eq(2L)))
                .thenReturn(List.of(family2Event));
        when(memberRepository.findAllByFamilyIdAndLeftAtIsNull(2L)).thenReturn(List.of(member(9L)));
        when(bindRepository.findAllByUserIdIn(anyCollection())).thenReturn(List.of(bind(9L, "o9")));

        jobs.sendDailyEventDigest();

        verify(notifyService, times(1)).notifyEventDigest(List.of("o9"), 1, "08:00 复诊");
    }

    // 每日入口：「今日」取自注入时钟（一次性日程按固定今日命中）
    @Test
    void daily_job_resolves_today_from_injected_clock() {
        when(eventRepository.findDistinctFamilyIdsWithActiveEvents()).thenReturn(List.of(FAMILY_ID));
        stubActiveEvents(oneTimeEvent(116L, "07:30", "打太极", TODAY));
        stubFamilyBoundMembers("1:o1");

        jobs.sendDailyEventDigest();

        verify(notifyService).notifyEventDigest(List.of("o1"), 1, "07:30 打太极");
    }
}
