package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.*;
import com.familyapp.task.TaskPriority;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class WechatCandidatePreviewTest {
    private TurnRequest request() {
        return new TurnRequest(1L, 0L, 0, 1L, "整理书架", null, List.of(), Map.of(),
                null, null, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void rendersStoredTaskDateAndExplicitRevisionCommandWithoutModelOpening() {
        var task = new CandidateTask("整理书架", "", LocalDate.of(2026, 10, 17), TaskPriority.MEDIUM, null, null);
        String text = WechatCandidatePreview.render(List.of(task), request(), 2);
        assertThat(text).contains("1. 整理书架", "日期：2026-10-17", "负责人：未指定", "尚未创建", "确认创建 2");
        assertThat(WechatCandidatePreview.segments(text)).containsExactly(text);
    }

    @Test
    void longChineseAndEmojiPreviewIsFullyPreservedWithinEachUtf8Budget() {
        var task = new CandidateTask("书".repeat(190) + "💡", "", null, TaskPriority.HIGH, null, null);
        String text = WechatCandidatePreview.render(Collections.nCopies(20, task), request(), 9);
        var parts = WechatCandidatePreview.segments(text);
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(String.join("", parts)).isEqualTo(text);
        assertThat(parts).allSatisfy(part -> {
            assertThat(part.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(1800);
            assertThat(new String(part.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(part);
        });
        assertThat(parts.get(parts.size() - 1)).contains("确认创建 9");
    }
}
