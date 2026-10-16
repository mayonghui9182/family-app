package com.familyapp.wechat.conversation;

import com.familyapp.wechat.conversation.WechatConversationAgentClient.CandidateTask;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 从已校验候选渲染确认清单，不依赖模型是否在回复中重复列出任务。 */
final class WechatCandidatePreview {
    private WechatCandidatePreview() { }

    static String render(List<CandidateTask> tasks, TurnRequest request, int revision) {
        StringBuilder text = new StringBuilder("待确认任务（第" + revision + "版，尚未创建）：\n");
        for (int index = 0; index < tasks.size(); index++) {
            CandidateTask task = tasks.get(index);
            text.append(index + 1).append(". ").append(task.title().replaceAll("\\s+", " ")).append("\n");
            text.append("日期：").append(task.dueDate() == null ? "未指定" : task.dueDate()).append("；优先级：")
                    .append(switch (task.priority()) { case HIGH -> "高"; case MEDIUM -> "中"; case LOW -> "低"; }).append("\n");
            String member = request.members().stream().filter(item -> item.profileId().equals(task.assigneeProfileId()))
                    .map(item -> item.displayName()).findFirst().orElse("未指定");
            String category = request.categories().stream().filter(item -> item.categoryId().equals(task.categoryId()))
                    .map(item -> item.name()).findFirst().orElse("未指定");
            if (task.categoryName() != null) {
                category = request.categories().stream().filter(item ->
                        WechatCategoryNames.key(item.name()).equals(WechatCategoryNames.key(task.categoryName())))
                        .map(item -> item.name()).findFirst()
                        .orElse(task.categoryName() + "（新建，确认后创建）");
            }
            text.append("负责人：").append(member.replaceAll("\\s+", " "))
                    .append("；分类：").append(category.replaceAll("\\s+", " ")).append("\n\n");
        }
        return text.append("请核对以上任务。回复“确认创建 ").append(revision)
                .append("”后才会创建；需要修改请直接说明。").toString();
    }

    /** 每段按 UTF-8 字节计数，保留全部候选，不切断中文或表情。 */
    static List<String> segments(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder part = new StringBuilder();
        int bytes = 0;
        for (int point : text.codePoints().toArray()) {
            String character = new String(Character.toChars(point));
            int size = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > 1800) { parts.add(part.toString()); part.setLength(0); bytes = 0; }
            part.append(character); bytes += size;
        }
        if (!part.isEmpty()) parts.add(part.toString());
        return List.copyOf(parts);
    }
}
