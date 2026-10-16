package com.familyapp.agent;

import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DirectTaskCreator.decomposeOrFallback 三态语义测试：
 * 拆解成功 → 不重复建任务；返回未拆解 / 调用异常 → 直建 + 中文提示便签。
 * （整分支终审发现的重复建任务缺陷的回归护栏。）
 */
class DirectTaskCreatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Long FID = 7L;

    private final TaskRepository tasks = mock(TaskRepository.class);
    private final AgentCallbackClient client = mock(AgentCallbackClient.class);
    private final NoteRepository notes = mock(NoteRepository.class);
    private final com.familyapp.wechat.WechatNotifyService notifyService =
            mock(com.familyapp.wechat.WechatNotifyService.class);
    private DirectTaskCreator creator;

    @BeforeEach
    void setUp() {
        creator = new DirectTaskCreator(tasks, client, notes, notifyService);
    }

    private com.fasterxml.jackson.databind.JsonNode node() {
        try {
            return MAPPER.readTree("{\"title\":\"AI 下游\",\"needs_ai_decomposition\":true}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 拆解成功_不重复建任务不落便签() {
        when(client.decompose(anyLong(), any(), any(), any()))
                .thenReturn(new AgentCallbackClient.DecomposeResult(true, 99L, null));

        assertThat(creator.decomposeOrFallback(FID, node(), TaskSource.TRIGGER)).isNull();
        verify(tasks, never()).save(any());
        verify(notes, never()).save(any());
    }

    @Test
    void 返回未拆解_直建并附便签() {
        when(client.decompose(anyLong(), any(), any(), any()))
                .thenReturn(new AgentCallbackClient.DecomposeResult(false, null, "超时"));
        when(tasks.save(any())).thenAnswer(inv -> {
            com.familyapp.task.Task t = inv.getArgument(0);
            t.setId(1L);
            return t;
        });

        creator.decomposeOrFallback(FID, node(), TaskSource.TRIGGER);

        verify(tasks, times(1)).save(any());
        verify(notes, times(1)).save(any(Note.class));
    }

    @Test
    void 调用异常_直建并附便签() {
        when(client.decompose(anyLong(), any(), any(), any()))
                .thenThrow(new IllegalStateException("connect refused"));
        when(tasks.save(any())).thenAnswer(inv -> {
            com.familyapp.task.Task t = inv.getArgument(0);
            t.setId(2L);
            return t;
        });

        creator.decomposeOrFallback(FID, node(), TaskSource.TRIGGER);

        verify(tasks, times(1)).save(any());
        verify(notes, times(1)).save(any(Note.class));
    }
}
