package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.family.common.context.UserContext;
import com.family.config.BaiduSpeechConfig;
import com.family.dto.TodoDTO;
import com.family.entity.Reminder;
import com.family.entity.Todo;
import com.family.mapper.ReminderMapper;
import com.family.mapper.TodoMapper;
import com.family.vo.PageVO;
import com.family.vo.TodoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 待办服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TodoService {

    private final TodoMapper todoMapper;
    private final ReminderMapper reminderMapper;
    private final BaiduSpeechService baiduSpeechService;
    private final BaiduSpeechConfig baiduSpeechConfig;

    @Value("${file.upload-path:./uploads}")
    private String uploadPath;

    /**
     * 获取今日待办列表
     *
     * @return 今日待办列表
     */
    public List<TodoVO> getTodayList() {
        Long familyId = UserContext.getFamilyId();
        LocalDate today = LocalDate.now();

        LambdaQueryWrapper<Todo> wrapper = new LambdaQueryWrapper<Todo>()
                .eq(Todo::getFamilyId, familyId)
                .and(w -> w.eq(Todo::getDueDate, today)
                        .or()
                        .eq(Todo::getCompleted, 0))
                .orderByAsc(Todo::getCompleted)
                .orderByAsc(Todo::getDueTime)
                .orderByDesc(Todo::getCreateTime);

        List<Todo> todos = todoMapper.selectList(wrapper);
        return todos.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    /**
     * 分页获取待办列表
     *
     * @param page     页码
     * @param size     每页大小
     * @param status   状态（completed/pending/null）
     * @param category 分类
     * @return 分页结果
     */
    public PageVO<TodoVO> getList(int page, int size, String status, String category) {
        Long familyId = UserContext.getFamilyId();

        LambdaQueryWrapper<Todo> wrapper = new LambdaQueryWrapper<Todo>()
                .eq(Todo::getFamilyId, familyId)
                .orderByAsc(Todo::getCompleted)
                .orderByDesc(Todo::getCreateTime);

        if ("completed".equals(status)) {
            wrapper.eq(Todo::getCompleted, 1);
        } else if ("pending".equals(status)) {
            wrapper.eq(Todo::getCompleted, 0);
        }

        if (category != null && !category.isEmpty()) {
            wrapper.eq(Todo::getCategory, category);
        }

        Page<Todo> pageParam = new Page<>(page, size);
        Page<Todo> todoPage = todoMapper.selectPage(pageParam, wrapper);

        List<TodoVO> records = todoPage.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        return new PageVO<>(
                records,
                todoPage.getTotal(),
                todoPage.getCurrent(),
                todoPage.getSize(),
                todoPage.getPages()
        );
    }

    /**
     * 创建待办
     *
     * @param dto 待办DTO
     * @return 创建的待办
     */
    @Transactional(rollbackFor = Exception.class)
    public TodoVO create(TodoDTO dto) {
        Long userId = UserContext.getUserId();
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        String contentType = dto.getContentType() != null ? dto.getContentType() : "text";
        String title = dto.getTitle();
        String content = dto.getContent();

        if ("voice".equals(contentType) && baiduSpeechConfig.isConfigured() && dto.getVoiceUrl() != null) {
            try {
                String recognizedText = recognizeVoice(dto.getVoiceUrl());
                if (recognizedText != null && !recognizedText.isEmpty()) {
                    if (title == null || title.isEmpty()) {
                        title = recognizedText.length() > 50 ? recognizedText.substring(0, 50) : recognizedText;
                    }
                    if (content == null || content.isEmpty()) {
                        content = recognizedText;
                    }
                    log.info("语音待办识别成功: voiceUrl={}, text={}", dto.getVoiceUrl(), recognizedText);
                }
            } catch (Exception e) {
                log.warn("语音待办识别失败: voiceUrl={}, error={}", dto.getVoiceUrl(), e.getMessage());
            }
        }

        Todo todo = new Todo();
        todo.setUserId(userId);
        todo.setFamilyId(familyId);
        todo.setTitle(title);
        todo.setContentType(contentType);
        todo.setVoiceUrl(dto.getVoiceUrl());
        todo.setVoiceDuration(dto.getVoiceDuration() != null ? dto.getVoiceDuration() : 0);
        todo.setContent(content);
        todo.setCategory(dto.getCategory());
        todo.setPriority(dto.getPriority() != null ? dto.getPriority() : "medium");
        todo.setDeadline(dto.getDeadline());
        todo.setDueDate(dto.getDueDate());
        todo.setDueTime(dto.getDueTime());
        todo.setCompleted(0);
        todo.setSortOrder(0);
        todo.setRemark(dto.getRemark());
        todo.setCreatedBy(userId);
        todo.setCreateTime(now);
        todo.setUpdateTime(now);

        todoMapper.insert(todo);

        if (Boolean.TRUE.equals(dto.getHasReminder())) {
            createReminderForTodo(todo, dto);
        }

        return convertToVO(todo);
    }

    /**
     * 识别语音文件
     *
     * @param voiceUrl 语音文件URL
     * @return 识别的文字
     */
    private String recognizeVoice(String voiceUrl) {
        try {
            String filePath = voiceUrl;
            if (voiceUrl.startsWith("/uploads/")) {
                filePath = uploadPath + File.separator + voiceUrl.substring("/uploads/".length());
            }

            File audioFile = new File(filePath);
            if (!audioFile.exists()) {
                log.warn("语音文件不存在: {}", filePath);
                return null;
            }

            byte[] audioData = Files.readAllBytes(audioFile.toPath());

            String format = "wav";
            String fileName = audioFile.getName().toLowerCase();
            if (fileName.endsWith(".pcm")) {
                format = "pcm";
            } else if (fileName.endsWith(".wav")) {
                format = "wav";
            } else if (fileName.endsWith(".amr")) {
                format = "amr";
            } else if (fileName.endsWith(".m4a")) {
                format = "m4a";
            }

            return baiduSpeechService.recognize(audioData, format);
        } catch (Exception e) {
            log.error("语音识别异常", e);
            return null;
        }
    }

    /**
     * 切换完成状态
     *
     * @param id 待办ID
     * @return 切换后的待办
     */
    @Transactional(rollbackFor = Exception.class)
    public TodoVO toggleComplete(Long id) {
        Long userId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();

        Todo todo = todoMapper.selectById(id);
        if (todo == null) {
            return null;
        }

        if (todo.getCompleted() == 0) {
            todo.setCompleted(1);
            todo.setCompletedBy(userId);
            todo.setCompletedAt(now);
            todo.setCompletedTime(now);
            handleReminderOnComplete(todo.getId());
        } else {
            todo.setCompleted(0);
            todo.setCompletedBy(null);
            todo.setCompletedAt(null);
            todo.setCompletedTime(null);
        }
        todo.setUpdateTime(now);

        todoMapper.updateById(todo);
        return convertToVO(todo);
    }

    /**
     * 删除待办
     *
     * @param id 待办ID
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        LambdaQueryWrapper<Reminder> wrapper = new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getTodoId, id);
        reminderMapper.delete(wrapper);

        return todoMapper.deleteById(id) > 0;
    }

    /**
     * 为待办创建关联提醒
     */
    private void createReminderForTodo(Todo todo, TodoDTO dto) {
        Long userId = UserContext.getUserId();
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setFamilyId(familyId);
        reminder.setTodoId(todo.getId());
        reminder.setTitle(todo.getTitle() != null ? todo.getTitle() : todo.getContent());
        reminder.setContent(todo.getContent());
        reminder.setCategory(todo.getCategory());
        reminder.setTimeType(dto.getRemindTimeType() != null ? dto.getRemindTimeType() : "absolute");
        reminder.setRelativeMinutes(dto.getRemindRelativeMinutes() != null ? dto.getRemindRelativeMinutes() : 0);
        reminder.setRepeatType(dto.getRepeatType() != null ? dto.getRepeatType() : "none");
        reminder.setRepeatConfig(dto.getRepeatConfig());
        reminder.setEnabled(1);
        reminder.setReminded(0);
        reminder.setCompletedToday(0);
        reminder.setRemindCount(0);
        reminder.setCreatedBy(userId);
        reminder.setCreateTime(now);
        reminder.setUpdateTime(now);

        LocalDateTime nextRemindAt = calculateNextRemindAt(dto);
        reminder.setNextRemindAt(nextRemindAt);
        if (nextRemindAt != null) {
            reminder.setNextRemindDate(nextRemindAt.toLocalDate());
            reminder.setRemindTime(nextRemindAt.toLocalTime());
        }

        reminderMapper.insert(reminder);
    }

    /**
     * 计算下次提醒时间
     */
    private LocalDateTime calculateNextRemindAt(TodoDTO dto) {
        String timeType = dto.getRemindTimeType() != null ? dto.getRemindTimeType() : "absolute";

        if ("absolute".equals(timeType)) {
            if (dto.getDueDate() != null && dto.getDueTime() != null) {
                return LocalDateTime.of(dto.getDueDate(), dto.getDueTime());
            } else if (dto.getDueDate() != null) {
                return LocalDateTime.of(dto.getDueDate(), LocalTime.of(9, 0));
            }
            return null;
        } else if ("relative".equals(timeType)) {
            int minutes = dto.getRemindRelativeMinutes() != null ? dto.getRemindRelativeMinutes() : 0;
            return LocalDateTime.now().plusMinutes(minutes);
        }

        return null;
    }

    /**
     * 待办完成时处理关联提醒
     */
    private void handleReminderOnComplete(Long todoId) {
        LambdaQueryWrapper<Reminder> wrapper = new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getTodoId, todoId);
        List<Reminder> reminders = reminderMapper.selectList(wrapper);

        LocalDateTime now = LocalDateTime.now();
        for (Reminder reminder : reminders) {
            String repeatType = reminder.getRepeatType();
            if (repeatType == null || "none".equals(repeatType)) {
                reminder.setEnabled(0);
            } else {
                reminder.setCompletedToday(1);
            }
            reminder.setUpdateTime(now);
            reminderMapper.updateById(reminder);
        }
    }

    /**
     * 转换为VO
     */
    private TodoVO convertToVO(Todo todo) {
        TodoVO vo = new TodoVO();
        vo.setId(todo.getId());
        vo.setFamilyId(todo.getFamilyId());
        vo.setTitle(todo.getTitle());
        vo.setContentType(todo.getContentType());
        vo.setVoiceUrl(todo.getVoiceUrl());
        vo.setVoiceDuration(todo.getVoiceDuration());
        vo.setContent(todo.getContent());
        vo.setCategory(todo.getCategory());
        vo.setPriority(todo.getPriority());
        vo.setDeadline(todo.getDeadline());
        vo.setDueDate(todo.getDueDate());
        vo.setDueTime(todo.getDueTime());
        vo.setCompleted(todo.getCompleted());
        vo.setCompletedTime(todo.getCompletedTime());
        vo.setCompletedBy(todo.getCompletedBy());
        vo.setCompletedAt(todo.getCompletedAt());
        vo.setRemark(todo.getRemark());
        vo.setCreatedBy(todo.getCreatedBy());
        vo.setCreateTime(todo.getCreateTime());
        return vo;
    }
}
