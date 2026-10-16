package com.familyapp.wechat.conversation;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信会话确定性命令解析器。
 *
 * <p>命令使用完全匹配并在调用 AI 前解析，优先级依次为家庭、会话、
 * 历史、任务确认和普通文本。</p>
 */
@Component
public class WechatConversationCommandParser {

    private static final Set<String> FAMILY_LIST_COMMANDS =
            Set.of("切换家庭", "家庭列表", "我的家庭");
    private static final Pattern SWITCH_FAMILY =
            Pattern.compile("^切换(?:到|至)\\s*(.+)$");
    private static final Pattern CONTINUE_HISTORY =
            Pattern.compile("^继续\\s*(\\d+)$");
    private static final Pattern DELETE_HISTORY =
            Pattern.compile("^删除\\s*(\\d+)$");
    private static final Pattern CONFIRM_DELETE_HISTORY =
            Pattern.compile("^确认删除\\s*(\\d+)$");
    private static final Pattern CONFIRM_CREATE =
            Pattern.compile("^确认创建(?:\\s*(\\d+))?$");

    public ParsedCommand parse(String input) {
        return parse(input, ParseContext.withoutPendingFamilySelection());
    }

    public ParsedCommand parse(String input, ParseContext context) {
        String normalized = normalize(input);
        if (normalized.isEmpty()) {
            return ParsedCommand.text(input);
        }

        if (FAMILY_LIST_COMMANDS.contains(normalized)) {
            return ParsedCommand.simple(CommandType.LIST_FAMILIES);
        }
        if ("默认家庭".equals(normalized)) return ParsedCommand.simple(CommandType.DEFAULT_FAMILY);
        if (normalized.startsWith("设置默认家庭 ")) {
            return new ParsedCommand(CommandType.SET_DEFAULT_FAMILY, null,
                    normalized.substring("设置默认家庭 ".length()).strip());
        }
        if ("当前家庭".equals(normalized)) {
            return ParsedCommand.simple(CommandType.CURRENT_FAMILY);
        }

        Matcher switchFamily = SWITCH_FAMILY.matcher(normalized);
        if (switchFamily.matches()) {
            String familyName = switchFamily.group(1).strip();
            if (!familyName.isEmpty()) {
                return ParsedCommand.familySelection(familyName);
            }
        }

        if (context.awaitingFamilySelection()) {
            return ParsedCommand.familySelection(normalized);
        }

        if ("新建任务".equals(normalized)) {
            return ParsedCommand.simple(CommandType.NEW_TASK);
        }
        if ("当前摘要".equals(normalized)) {
            return ParsedCommand.simple(CommandType.CURRENT_SUMMARY);
        }
        if ("取消会话".equals(normalized)) {
            return ParsedCommand.simple(CommandType.CANCEL_CONVERSATION);
        }

        if ("历史会话".equals(normalized)) {
            return ParsedCommand.simple(CommandType.LIST_HISTORY);
        }

        Integer continueIndex = positiveNumber(CONTINUE_HISTORY.matcher(normalized));
        if (continueIndex != null) {
            return ParsedCommand.numbered(CommandType.CONTINUE_HISTORY, continueIndex);
        }

        Integer deleteIndex = positiveNumber(DELETE_HISTORY.matcher(normalized));
        if (deleteIndex != null) {
            return ParsedCommand.numbered(CommandType.DELETE_HISTORY, deleteIndex);
        }

        if ("清空历史".equals(normalized)) {
            return ParsedCommand.simple(CommandType.CLEAR_HISTORY);
        }

        Integer confirmDeleteIndex =
                positiveNumber(CONFIRM_DELETE_HISTORY.matcher(normalized));
        if (confirmDeleteIndex != null) {
            return ParsedCommand.numbered(
                    CommandType.CONFIRM_DELETE_HISTORY,
                    confirmDeleteIndex);
        }

        if ("确认清空".equals(normalized)) {
            return ParsedCommand.simple(CommandType.CONFIRM_CLEAR_HISTORY);
        }

        Matcher confirmCreate = CONFIRM_CREATE.matcher(normalized);
        if (confirmCreate.matches()) {
            String revisionText = confirmCreate.group(1);
            if (revisionText == null) {
                return ParsedCommand.simple(CommandType.CONFIRM_CREATE);
            }
            Integer revision = positiveNumber(revisionText);
            if (revision != null) {
                return ParsedCommand.numbered(CommandType.CONFIRM_CREATE, revision);
            }
        }

        if (FamilyTaskExperienceService.preference(normalized) != null) {
            return new ParsedCommand(CommandType.SET_TASK_PREFERENCE, null, normalized);
        }
        return ParsedCommand.text(input);
    }

    private Integer positiveNumber(Matcher matcher) {
        if (!matcher.matches()) {
            return null;
        }
        return positiveNumber(matcher.group(1));
    }

    private Integer positiveNumber(String value) {
        try {
            int number = Integer.parseInt(value);
            return number > 0 ? number : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalize(String input) {
        if (input == null) {
            return "";
        }
        return Normalizer.normalize(input, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Z}\\s]+", " ")
                .strip();
    }

    public enum CommandType {
        LIST_FAMILIES,
        CURRENT_FAMILY,
        DEFAULT_FAMILY,
        SET_DEFAULT_FAMILY,
        SELECT_FAMILY,
        NEW_TASK,
        CURRENT_SUMMARY,
        CANCEL_CONVERSATION,
        LIST_HISTORY,
        CONTINUE_HISTORY,
        DELETE_HISTORY,
        CLEAR_HISTORY,
        CONFIRM_DELETE_HISTORY,
        CONFIRM_CLEAR_HISTORY,
        CONFIRM_CREATE,
        SET_TASK_PREFERENCE,
        ORDINARY_TEXT
    }

    public record ParseContext(boolean awaitingFamilySelection) {

        public static ParseContext withoutPendingFamilySelection() {
            return new ParseContext(false);
        }

        public static ParseContext waitingForFamilySelection() {
            return new ParseContext(true);
        }
    }

    public record ParsedCommand(
            CommandType type,
            Integer number,
            String value) {

        static ParsedCommand simple(CommandType type) {
            return new ParsedCommand(type, null, null);
        }

        static ParsedCommand numbered(CommandType type, int number) {
            return new ParsedCommand(type, number, null);
        }

        static ParsedCommand familySelection(String value) {
            return new ParsedCommand(CommandType.SELECT_FAMILY, null, value);
        }

        static ParsedCommand text(String value) {
            return new ParsedCommand(
                    CommandType.ORDINARY_TEXT,
                    null,
                    value == null ? "" : value);
        }
    }
}
