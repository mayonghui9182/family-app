package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.common.context.UserContext;
import com.family.dto.ReminderDTO;
import com.family.entity.Reminder;
import com.family.mapper.ReminderMapper;
import com.family.vo.ReminderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 提醒服务
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderMapper reminderMapper;

    /**
     * 获取家庭的提醒列表
     *
     * @return 提醒列表
     */
    public List<ReminderVO> getList() {
        Long familyId = UserContext.getFamilyId();

        List<Reminder> reminders = reminderMapper.selectList(
                new LambdaQueryWrapper<Reminder>()
                        .eq(Reminder::getFamilyId, familyId)
                        .orderByDesc(Reminder::getNextRemindAt)
                        .orderByDesc(Reminder::getCreateTime)
        );

        return reminders.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    /**
     * 新增提醒
     *
     * @param dto 提醒DTO
     * @return 新增的提醒
     */
    @Transactional(rollbackFor = Exception.class)
    public ReminderVO create(ReminderDTO dto) {
        Long userId = UserContext.getUserId();
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        Reminder reminder = new Reminder();
        reminder.setUserId(userId);
        reminder.setFamilyId(familyId);
        reminder.setTitle(dto.getTitle());
        reminder.setContent(dto.getContent());
        reminder.setType(dto.getType());
        reminder.setRepeatType(dto.getRepeatType() != null ? dto.getRepeatType() : "none");
        reminder.setEnabled(dto.getEnabled() != null ? dto.getEnabled() : 1);
        reminder.setReminded(0);
        reminder.setCompletedToday(0);
        reminder.setRemindCount(0);
        reminder.setRemark(dto.getRemark());
        reminder.setCreatedBy(userId);
        reminder.setCreateTime(now);
        reminder.setUpdateTime(now);

        if (dto.getRemindTime() != null) {
            reminder.setRemindTime(dto.getRemindTime().toLocalTime());
            reminder.setNextRemindAt(dto.getRemindTime());
            reminder.setNextRemindDate(dto.getRemindTime().toLocalDate());
        }

        reminderMapper.insert(reminder);
        return convertToVO(reminder);
    }

    /**
     * 修改提醒
     *
     * @param id  提醒ID
     * @param dto 提醒DTO
     * @return 修改后的提醒
     */
    @Transactional(rollbackFor = Exception.class)
    public ReminderVO update(Long id, ReminderDTO dto) {
        Long familyId = UserContext.getFamilyId();
        Reminder reminder = reminderMapper.selectById(id);
        if (reminder == null || !reminder.getFamilyId().equals(familyId)) {
            return null;
        }

        if (dto.getTitle() != null) {
            reminder.setTitle(dto.getTitle());
        }
        if (dto.getContent() != null) {
            reminder.setContent(dto.getContent());
        }
        if (dto.getType() != null) {
            reminder.setType(dto.getType());
        }
        if (dto.getRemindTime() != null) {
            reminder.setRemindTime(dto.getRemindTime().toLocalTime());
            reminder.setNextRemindAt(dto.getRemindTime());
            reminder.setNextRemindDate(dto.getRemindTime().toLocalDate());
        }
        if (dto.getRepeatType() != null) {
            reminder.setRepeatType(dto.getRepeatType());
        }
        if (dto.getEnabled() != null) {
            reminder.setEnabled(dto.getEnabled());
        }
        if (dto.getRemark() != null) {
            reminder.setRemark(dto.getRemark());
        }
        reminder.setUpdateTime(LocalDateTime.now());

        reminderMapper.updateById(reminder);
        return convertToVO(reminder);
    }

    /**
     * 删除提醒
     *
     * @param id 提醒ID
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        Long familyId = UserContext.getFamilyId();
        Reminder reminder = reminderMapper.selectById(id);
        if (reminder == null || !reminder.getFamilyId().equals(familyId)) {
            return false;
        }
        return reminderMapper.deleteById(id) > 0;
    }

    /**
     * 启用/禁用提醒
     *
     * @param id 提醒ID
     * @return 切换后的提醒
     */
    @Transactional(rollbackFor = Exception.class)
    public ReminderVO toggle(Long id) {
        Long familyId = UserContext.getFamilyId();
        Reminder reminder = reminderMapper.selectById(id);
        if (reminder == null || !reminder.getFamilyId().equals(familyId)) {
            return null;
        }

        reminder.setEnabled(reminder.getEnabled() == 1 ? 0 : 1);
        reminder.setUpdateTime(LocalDateTime.now());
        reminderMapper.updateById(reminder);

        return convertToVO(reminder);
    }

    /**
     * 稍后提醒
     * 设置 nextRemindAt = 当前时间 + minutes 分钟，remindCount 重置为0
     *
     * @param id      提醒ID
     * @param minutes 延后分钟数
     * @return 更新后的提醒
     */
    @Transactional(rollbackFor = Exception.class)
    public ReminderVO snooze(Long id, int minutes) {
        Long familyId = UserContext.getFamilyId();
        Reminder reminder = reminderMapper.selectById(id);
        if (reminder == null || !reminder.getFamilyId().equals(familyId)) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now();
        reminder.setNextRemindAt(now.plusMinutes(minutes));
        reminder.setRemindCount(0);
        reminder.setEnabled(1);
        reminder.setUpdateTime(now);

        reminderMapper.updateById(reminder);
        return convertToVO(reminder);
    }

    /**
     * 转换为VO
     */
    private ReminderVO convertToVO(Reminder reminder) {
        ReminderVO vo = new ReminderVO();
        vo.setId(reminder.getId());
        vo.setTitle(reminder.getTitle());
        vo.setContent(reminder.getContent());
        vo.setType(reminder.getType());
        vo.setRepeatType(reminder.getRepeatType());
        vo.setEnabled(reminder.getEnabled());
        vo.setReminded(reminder.getReminded());
        vo.setRemark(reminder.getRemark());
        vo.setCreateTime(reminder.getCreateTime());
        if (reminder.getNextRemindAt() != null) {
            vo.setRemindTime(reminder.getNextRemindAt());
        }
        return vo;
    }
}
