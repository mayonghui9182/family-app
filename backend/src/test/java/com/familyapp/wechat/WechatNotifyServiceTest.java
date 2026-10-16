package com.familyapp.wechat;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.task.Task;
import com.familyapp.task.TaskRepository;
import com.familyapp.user.User;
import com.familyapp.wechat.WechatNotifyDtos.WechatNotifyMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Change 12：通知组装与接收人解析（纯 Mockito，不起 Spring 上下文）。
 * 覆盖：接收人=家庭未退出成员中有绑定者；双零跳过；三事件参数；异常隔离。
 */
class WechatNotifyServiceTest {

    private WechatBindRepository bindRepo;
    private FamilyMemberRepository memberRepo;
    private FamilyRepository familyRepo;
    private TaskRepository taskRepo;
    private WechatNotifyClient client;
    private WechatNotifyService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 6);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @BeforeEach
    void setUp() {
        bindRepo = mock(WechatBindRepository.class);
        memberRepo = mock(FamilyMemberRepository.class);
        familyRepo = mock(FamilyRepository.class);
        taskRepo = mock(TaskRepository.class);
        client = mock(WechatNotifyClient.class);
        // 固定时钟：服务内所有「今天」取自 TODAY，断言不再随系统日期漂移
        Clock clock = Clock.fixed(TODAY.atStartOfDay(ZONE).toInstant(), ZONE);
        when(memberRepo.findByFamilyIdAndUserIdAndLeftAtIsNull(10L, 7L))
                .thenReturn(Optional.of(member(7L)));
        service = new WechatNotifyService(bindRepo, memberRepo, familyRepo, taskRepo, client, clock, mock(com.familyapp.profile.PersonProfileRepository.class),
                mock(com.familyapp.wechat.TaskNotificationQueue.class));
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

    @SuppressWarnings("unchecked")
    private List<WechatNotifyMessage> captureMessages() {
        ArgumentCaptor<List<WechatNotifyMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).sendMessages(captor.capture());
        return captor.getValue();
    }

    @Test
    void digest_sends_to_bound_members_with_correct_counts() {
        when(memberRepo.findAllByFamilyIdAndLeftAtIsNull(10L)).thenReturn(
                List.of(member(1L), member(2L), member(3L)));
        when(bindRepo.findAllByUserIdIn(anyCollection())).thenReturn(
                List.of(bind(1L, "o1"), bind(2L, "o2")));  // 成员 3 未绑定
        when(taskRepo.countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDate(
                eq(10L), any(), eq(TODAY))).thenReturn(2L);
        when(taskRepo.countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDateBefore(
                eq(10L), any(), eq(TODAY))).thenReturn(1L);

        var result = service.digestForFamily(10L, TODAY);

        assertThat(result.sent()).isTrue();
        assertThat(result.recipients()).isEqualTo(2);
        List<WechatNotifyMessage> messages = captureMessages();
        assertThat(messages).hasSize(2);
        assertThat(messages).allSatisfy(m -> {
            assertThat(m.event()).isEqualTo("task_digest");
            assertThat(m.params()).containsEntry("today_due", 2L).containsEntry("overdue", 1L);
        });
        assertThat(messages).extracting(WechatNotifyMessage::openid).containsExactly("o1", "o2");
    }

    @Test
    void digest_skips_when_no_tasks_today_or_overdue() {
        when(memberRepo.findAllByFamilyIdAndLeftAtIsNull(10L)).thenReturn(List.of(member(1L)));
        when(bindRepo.findAllByUserIdIn(anyCollection())).thenReturn(List.of(bind(1L, "o1")));
        when(taskRepo.countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDate(
                eq(10L), any(), eq(TODAY))).thenReturn(0L);
        when(taskRepo.countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDateBefore(
                eq(10L), any(), eq(TODAY))).thenReturn(0L);

        var result = service.digestForFamily(10L, TODAY);

        assertThat(result.sent()).isFalse();
        verify(client, never()).sendMessages(any());
    }

    @Test
    void digest_skips_when_no_bound_members() {
        when(memberRepo.findAllByFamilyIdAndLeftAtIsNull(10L)).thenReturn(
                List.of(member(1L), member(2L)));
        when(bindRepo.findAllByUserIdIn(anyCollection())).thenReturn(List.of());
        when(taskRepo.countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDate(
                eq(10L), any(), eq(TODAY))).thenReturn(3L);
        when(taskRepo.countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDateBefore(
                eq(10L), any(), eq(TODAY))).thenReturn(0L);

        var result = service.digestForFamily(10L, TODAY);

        assertThat(result.sent()).isFalse();
        verify(client, never()).sendMessages(any());
    }

    @Test
    void summary_ready_sends_family_name_and_date() {
        Family family = new Family();
        family.setId(10L);
        family.setName("老王家");
        when(familyRepo.findById(10L)).thenReturn(Optional.of(family));
        when(memberRepo.findAllByFamilyIdAndLeftAtIsNull(10L)).thenReturn(List.of(member(1L)));
        when(bindRepo.findAllByUserIdIn(anyCollection())).thenReturn(List.of(bind(1L, "o1")));

        service.notifySummaryReady(10L);

        List<WechatNotifyMessage> messages = captureMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).event()).isEqualTo("summary_ready");
        assertThat(messages.get(0).openid()).isEqualTo("o1");
        assertThat(messages.get(0).params()).containsEntry("family_name", "老王家");
        assertThat(messages.get(0).params().get("date")).asString().isEqualTo(TODAY.toString());
    }

    @Test
    void summary_ready_skips_without_binding() {
        when(memberRepo.findAllByFamilyIdAndLeftAtIsNull(10L)).thenReturn(List.of(member(1L)));
        when(bindRepo.findAllByUserIdIn(anyCollection())).thenReturn(List.of());

        service.notifySummaryReady(10L);

        verify(client, never()).sendMessages(any());
    }

    @Test
    void task_assigned_notifies_bound_assignee_with_title_and_due() {
        Task task = new Task();
        task.setId(99L);
        task.setTitle("买菜");
        task.setFamilyId(10L);
        task.setAssigneeId(7L);
        task.setDueDate(LocalDate.of(2026, 9, 7));
        when(bindRepo.findByUserId(7L)).thenReturn(Optional.of(bind(7L, "o7")));

        service.notifyTaskAssigned(task);

        List<WechatNotifyMessage> messages = captureMessages();
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).event()).isEqualTo("task_assigned");
        assertThat(messages.get(0).openid()).isEqualTo("o7");
        assertThat(messages.get(0).params()).containsEntry("title", "买菜")
                .containsEntry("due_date", "2026-09-07");
    }

    @Test
    void task_assigned_without_assignee_id_makes_no_call() {
        Task task = new Task();
        task.setTitle("无主任务");

        service.notifyTaskAssigned(task);

        verify(client, never()).sendMessages(any());
    }

    @Test
    void task_assigned_without_binding_makes_no_call() {
        Task task = new Task();
        task.setAssigneeId(8L);
        when(bindRepo.findByUserId(8L)).thenReturn(Optional.empty());

        service.notifyTaskAssigned(task);

        verify(client, never()).sendMessages(any());
    }

    @Test
    void task_assigned_with_null_due_uses_null_param() {
        Task task = new Task();
        task.setTitle("随手事");
        task.setFamilyId(10L);
        task.setAssigneeId(7L);
        when(bindRepo.findByUserId(7L)).thenReturn(Optional.of(bind(7L, "o7")));

        service.notifyTaskAssigned(task);

        List<WechatNotifyMessage> messages = captureMessages();
        assertThat(messages.get(0).params()).containsEntry("title", "随手事")
                .containsEntry("due_date", null);
    }

    @Test
    void client_exception_is_swallowed_and_does_not_propagate() {
        when(memberRepo.findAllByFamilyIdAndLeftAtIsNull(10L)).thenReturn(List.of(member(1L)));
        when(bindRepo.findAllByUserIdIn(anyCollection())).thenReturn(List.of(bind(1L, "o1")));
        when(taskRepo.countByFamilyIdAndDeletedAtIsNullAndStatusNotInAndDueDate(
                eq(10L), any(), eq(TODAY))).thenReturn(5L);
        when(client.sendMessages(any())).thenThrow(new RuntimeException("模拟 wechat-service 宕机"));

        assertThatCode(() -> service.notifyDigest(10L, TODAY)).doesNotThrowAnyException();
    }
}
