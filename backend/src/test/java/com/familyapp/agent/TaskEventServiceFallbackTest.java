package com.familyapp.agent;

import com.familyapp.task.Task;
import com.familyapp.task.TaskSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C-17 触发服务单元测试：AI/非 AI 路由与 5 分钟去重。
 * 纯 Mockito（不启动 Spring 上下文）。AI 降级细节由 DirectTaskCreator 承担，
 * 其端到端语义（直建 + 中文提示便签）由 InternalAgentC1ToC18Test.C17_aiFallback 覆盖。
 */
class TaskEventServiceFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long FID = 9L;

    private final TaskTriggerRepository triggers = mock(TaskTriggerRepository.class);
    private final DirectTaskCreator creator = mock(DirectTaskCreator.class);
    private final TaskEventService service = new TaskEventService(triggers, creator);

    private static TaskTrigger trigger(String targetJson) {
        try {
            TaskTrigger t = new TaskTrigger();
            t.setFamilyId(FID);
            t.setSourceTaskId(1L);
            t.setTargetTemplate(MAPPER.readTree(targetJson));
            t.setIsActive(true);
            return t;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void AI触发器_委托decomposeOrFallback() {
        when(triggers.findBySourceTaskIdAndIsActiveTrue(1L)).thenReturn(List.of(
                trigger("{\"title\":\"AI 下游\",\"needs_ai_decomposition\":true}")));

        assertThat(service.onCompleted(1L, FID)).isEqualTo(1);
        verify(creator, times(1))
                .decomposeOrFallback(eq(FID), any(), eq(TaskSource.TRIGGER));
        verify(creator, never()).createFromTemplate(any(), any(), any());
    }

    @Test
    void needsAi双键_旧式needs_ai_true同样走AI() {
        when(triggers.findBySourceTaskIdAndIsActiveTrue(2L)).thenReturn(List.of(
                trigger("{\"title\":\"旧式\",\"needs_ai\":true}")));

        service.onCompleted(2L, FID);
        verify(creator, times(1)).decomposeOrFallback(eq(FID), any(), eq(TaskSource.TRIGGER));
    }

    @Test
    void 非AI触发器_直建且不碰回调() {
        when(triggers.findBySourceTaskIdAndIsActiveTrue(3L)).thenReturn(List.of(
                trigger("{\"title\":\"普通下游\",\"needs_ai\":false}")));

        assertThat(service.onCompleted(3L, FID)).isEqualTo(1);
        verify(creator, never()).decomposeOrFallback(any(), any(), any());
        verify(creator, times(1)).createFromTemplate(eq(FID), any(), eq(TaskSource.TRIGGER));
    }

    @Test
    void 五分钟窗口内重复事件去重() {
        when(triggers.findBySourceTaskIdAndIsActiveTrue(4L)).thenReturn(List.of(
                trigger("{\"title\":\"只该建一次\",\"needs_ai\":false}")));

        assertThat(service.onCompleted(4L, FID)).isEqualTo(1);
        assertThat(service.onCompleted(4L, FID)).isZero();
        verify(creator, times(1)).createFromTemplate(any(), any(), any());
    }
}
