package com.familyapp.agent;

import com.familyapp.task.Task;
import com.familyapp.task.TaskPriority;
import com.familyapp.task.TaskRepository;
import com.familyapp.task.TaskSource;
import com.familyapp.task.TaskStatus;
import com.familyapp.wechat.WechatNotifyService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 由模板 JSONB 直建 PENDING 任务 + AI 拆解统一降级入口。
 * 供 B-1 降级 / C-17 事件触发 / Change 3 调度（daily routine 与一次性）复用。
 */
@Service
public class DirectTaskCreator {

    private static final Logger log = LoggerFactory.getLogger(DirectTaskCreator.class);

    private final TaskRepository taskRepository;
    private final AgentCallbackClient callbackClient;
    private final NoteRepository noteRepository;
    private final WechatNotifyService wechatNotifyService;

    public DirectTaskCreator(TaskRepository taskRepository,
                             AgentCallbackClient callbackClient,
                             NoteRepository noteRepository,
                             WechatNotifyService wechatNotifyService) {
        this.taskRepository = taskRepository;
        this.callbackClient = callbackClient;
        this.noteRepository = noteRepository;
        this.wechatNotifyService = wechatNotifyService;
    }

    /**
     * 纯直建：由模板 JSONB 生成 PENDING 任务（非 AI 路径）。
     * title 缺失视为非法输入抛 IllegalArgumentException（调用方按跳过处理并告警）。
     */
    public Task createFromTemplate(Long familyId, JsonNode node, TaskSource source) {
        String title = node.path("title").asText("");
        if (title.isBlank()) {
            throw new IllegalArgumentException("目标模板缺少 title，无法生成任务");
        }
        Task task = new Task();
        task.setFamilyId(familyId);
        task.setTitle(title);
        task.setDescription(node.path("description").asText(null));
        task.setPriority(parsePriority(node.path("priority").asText("MEDIUM")));
        if (node.hasNonNull("category_id")) {
            task.setCategoryId(node.path("category_id").asLong());
        }
        if (node.hasNonNull("assignee_id")) {
            task.setAssigneeId(node.path("assignee_id").asLong());
        }
        if (node.hasNonNull("due_date")) {
            task.setDueDate(LocalDate.parse(node.path("due_date").asText()));
        }
        task.setStatus(TaskStatus.PENDING);
        task.setSource(source);
        Task saved = taskRepository.save(task);
        // Change 12：指派给已绑定微信的成员时发模板消息（服务内部吞异常，不影响建任务）
        wechatNotifyService.notifyTaskAssigned(saved);
        return saved;
    }

    /**
     * AI 路径统一降级：先走 B-1 拆解，传输失败或返回 decomposed=false 时
     * 直建普通 PENDING 任务并附「AI 拆解未响应」中文提示便签（前端行旁灰色提示）。
     */
    public Task decomposeOrFallback(Long familyId, JsonNode node, TaskSource source) {
        boolean aiDone = false;
        try {
            var result = callbackClient.decompose(
                    familyId,
                    node.path("title").asText(""),
                    node.hasNonNull("description") ? node.path("description").asText() : null,
                    node.hasNonNull("category_id") ? node.path("category_id").asLong() : null);
            aiDone = result.decomposed();
            if (!aiDone) {
                log.warn("agent-service 返回未拆解（{}），降级直建", result.message());
            }
        } catch (Exception e) {
            log.warn("AI 拆解未响应，已为您创建普通待办任务，可后续手动添加子步骤：{}", e.getMessage());
        }
        if (aiDone) {
            // 拆解成功：agent-service 已自行经 C-3/C-4 落库下游任务，backend 不再重复建
            return null;
        }
        Task created = createFromTemplate(familyId, node, source);
        Note tip = new Note();
        tip.setFamilyId(familyId);
        tip.setTargetType("task");
        tip.setTargetId(created.getId());
        tip.setContent("AI 拆解未响应，已为您创建普通待办任务，可后续手动添加子步骤");
        noteRepository.save(tip);
        return created;
    }

    private TaskPriority parsePriority(String raw) {
        try {
            return TaskPriority.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TaskPriority.MEDIUM;
        }
    }
}
