package com.familyapp.wechat.conversation;

import org.junit.jupiter.api.Test;

import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.CANCEL_CONVERSATION;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.CLEAR_HISTORY;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.CONFIRM_CLEAR_HISTORY;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.CONFIRM_CREATE;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.CONFIRM_DELETE_HISTORY;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.CONTINUE_HISTORY;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.CURRENT_SUMMARY;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.DELETE_HISTORY;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.LIST_FAMILIES;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.LIST_HISTORY;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.NEW_TASK;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.ORDINARY_TEXT;
import static com.familyapp.wechat.conversation.WechatConversationCommandParser.CommandType.SELECT_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;

class WechatConversationCommandParserTest {

    private final WechatConversationCommandParser parser =
            new WechatConversationCommandParser();

    @Test
    void familyCommandsHaveHighestPriority() {
        assertThat(parser.parse("切换家庭").type()).isEqualTo(LIST_FAMILIES);

        var switchByName = parser.parse("切换到 一一家");
        assertThat(switchByName.type()).isEqualTo(SELECT_FAMILY);
        assertThat(switchByName.value()).isEqualTo("一一家");

        var challengeAnswer = parser.parse(
                "新建任务",
                WechatConversationCommandParser.ParseContext
                        .waitingForFamilySelection());
        assertThat(challengeAnswer.type()).isEqualTo(SELECT_FAMILY);
        assertThat(challengeAnswer.value()).isEqualTo("新建任务");
    }

    @Test
    void recognizesExactConversationControls() {
        assertThat(parser.parse("新建任务").type()).isEqualTo(NEW_TASK);
        assertThat(parser.parse("当前摘要").type()).isEqualTo(CURRENT_SUMMARY);
        assertThat(parser.parse("取消会话").type()).isEqualTo(CANCEL_CONVERSATION);
        assertThat(parser.parse("请新建任务").type()).isEqualTo(ORDINARY_TEXT);
    }

    @Test
    void recognizesHistoryCommandsAndKeepsDeleteConfirmationSeparate() {
        assertThat(parser.parse("历史会话").type()).isEqualTo(LIST_HISTORY);

        var resume = parser.parse("继续 2");
        assertThat(resume.type()).isEqualTo(CONTINUE_HISTORY);
        assertThat(resume.number()).isEqualTo(2);

        var delete = parser.parse("删除2");
        assertThat(delete.type()).isEqualTo(DELETE_HISTORY);
        assertThat(delete.number()).isEqualTo(2);

        assertThat(parser.parse("清空历史").type()).isEqualTo(CLEAR_HISTORY);

        var confirmDelete = parser.parse("确认删除 2");
        assertThat(confirmDelete.type()).isEqualTo(CONFIRM_DELETE_HISTORY);
        assertThat(confirmDelete.number()).isEqualTo(2);
        assertThat(parser.parse("确认清空").type()).isEqualTo(CONFIRM_CLEAR_HISTORY);
    }

    @Test
    void exactCreateConfirmationAcceptsOptionalPositiveRevision() {
        var firstRevision = parser.parse("确认创建");
        assertThat(firstRevision.type()).isEqualTo(CONFIRM_CREATE);
        assertThat(firstRevision.number()).isNull();

        var versioned = parser.parse("确认创建　２");
        assertThat(versioned.type()).isEqualTo(CONFIRM_CREATE);
        assertThat(versioned.number()).isEqualTo(2);
    }

    @Test
    void fuzzyAffirmationsAndExtraModificationTextNeverConfirmCreation() {
        assertThat(parser.parse("好的").type()).isEqualTo(ORDINARY_TEXT);
        assertThat(parser.parse("确认").type()).isEqualTo(ORDINARY_TEXT);
        assertThat(parser.parse("可以创建").type()).isEqualTo(ORDINARY_TEXT);
        assertThat(parser.parse("确认创建 2，改成明天").type())
                .isEqualTo(ORDINARY_TEXT);
        assertThat(parser.parse("确认创建 0").type()).isEqualTo(ORDINARY_TEXT);
        assertThat(parser.parse("确认删除 2").type())
                .isEqualTo(CONFIRM_DELETE_HISTORY);
    }
}
