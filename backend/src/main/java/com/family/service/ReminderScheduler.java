package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.family.entity.Baby;
import com.family.entity.BabyVaccine;
import com.family.entity.Reminder;
import com.family.entity.Todo;
import com.family.entity.Vaccine;
import com.family.mapper.BabyMapper;
import com.family.mapper.BabyVaccineMapper;
import com.family.mapper.ReminderMapper;
import com.family.mapper.TodoMapper;
import com.family.mapper.VaccineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 提醒定时调度器
 * 每分钟检查待触发的提醒，每天凌晨重置重复提醒状态
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReminderScheduler {

    private final ReminderMapper reminderMapper;
    private final TodoMapper todoMapper;
    private final PushService pushService;
    private final BabyVaccineMapper babyVaccineMapper;
    private final VaccineMapper vaccineMapper;
    private final BabyMapper babyMapper;

    /**
     * 每分钟检查待触发的提醒
     */
    @Scheduled(cron = "0 * * * * *")
    public void checkReminders() {
        LocalDateTime now = LocalDateTime.now();

        List<Reminder> reminders = reminderMapper.selectList(
                new LambdaQueryWrapper<Reminder>()
                        .eq(Reminder::getEnabled, 1)
                        .le(Reminder::getNextRemindAt, now)
                        .apply("remind_count < max_remind_count")
        );

        if (reminders.isEmpty()) {
            return;
        }

        log.info("检查到 {} 条待触发的提醒", reminders.size());

        for (Reminder reminder : reminders) {
            try {
                processReminder(reminder, now);
            } catch (Exception e) {
                log.error("处理提醒失败，reminderId={}", reminder.getId(), e);
            }
        }
    }

    /**
     * 处理单条提醒
     */
    private void processReminder(Reminder reminder, LocalDateTime now) {
        if (reminder.getTodoId() != null) {
            Todo todo = todoMapper.selectById(reminder.getTodoId());
            if (todo != null && todo.getCompleted() == 1) {
                if ("none".equals(reminder.getRepeatType()) || reminder.getRepeatType() == null) {
                    reminder.setEnabled(0);
                } else {
                    reminder.setCompletedToday(1);
                }
                reminder.setUpdateTime(now);
                reminderMapper.updateById(reminder);
                return;
            }
        }

        if (reminder.getRepeatType() != null && !"none".equals(reminder.getRepeatType())
                && reminder.getCompletedToday() != null && reminder.getCompletedToday() == 1) {
            return;
        }

        pushService.pushToFamily(reminder.getFamilyId(), reminder.getTitle(), reminder.getContent());

        reminder.setRemindCount(reminder.getRemindCount() + 1);
        reminder.setLastTriggeredAt(now);
        reminder.setUpdateTime(now);

        if (reminder.getRemindCount() < reminder.getMaxRemindCount()) {
            int snoozeMinutes = reminder.getSnoozeMinutes() != null ? reminder.getSnoozeMinutes() : 5;
            reminder.setNextRemindAt(now.plusMinutes(snoozeMinutes));
        } else if ("none".equals(reminder.getRepeatType()) || reminder.getRepeatType() == null) {
            reminder.setEnabled(0);
        } else {
            LocalDateTime nextCycleRemindAt = calculateNextCycleRemindAt(reminder, now);
            reminder.setNextRemindAt(nextCycleRemindAt);
            reminder.setRemindCount(0);
            if (nextCycleRemindAt != null) {
                reminder.setNextRemindDate(nextCycleRemindAt.toLocalDate());
            }
        }

        reminderMapper.updateById(reminder);
    }

    /**
     * 计算下一个周期的提醒时间
     */
    private LocalDateTime calculateNextCycleRemindAt(Reminder reminder, LocalDateTime now) {
        LocalTime remindTime = reminder.getRemindTime() != null ? reminder.getRemindTime() : LocalTime.of(9, 0);
        LocalDate today = now.toLocalDate();
        String repeatType = reminder.getRepeatType();

        switch (repeatType) {
            case "daily":
                return LocalDateTime.of(today.plusDays(1), remindTime);
            case "weekly":
                return LocalDateTime.of(today.plusWeeks(1), remindTime);
            case "monthly":
                return LocalDateTime.of(today.plusMonths(1), remindTime);
            default:
                return null;
        }
    }

    /**
     * 每天凌晨0点重置所有重复提醒的 completedToday 和 remindCount
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyReminders() {
        log.info("开始重置每日重复提醒状态");

        LambdaUpdateWrapper<Reminder> wrapper = new LambdaUpdateWrapper<Reminder>()
                .ne(Reminder::getRepeatType, "none")
                .isNotNull(Reminder::getRepeatType)
                .set(Reminder::getCompletedToday, 0)
                .set(Reminder::getRemindCount, 0)
                .set(Reminder::getUpdateTime, LocalDateTime.now());

        int rows = reminderMapper.update(null, wrapper);
        log.info("重置了 {} 条重复提醒的每日状态", rows);
    }

    /**
     * 每天早上8点检查疫苗接种提醒
     */
    @Scheduled(cron = "0 0 8 * * *")
    public void checkVaccineReminders() {
        log.info("开始检查疫苗接种提醒");

        LocalDate today = LocalDate.now();

        List<BabyVaccine> pendingVaccines = babyVaccineMapper.selectList(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getStatus, "pending")
                        .eq(BabyVaccine::getRemindEnabled, 1)
                        .eq(BabyVaccine::getReminded, 0)
                        .isNotNull(BabyVaccine::getPlannedDate)
        );

        if (pendingVaccines.isEmpty()) {
            log.info("没有待提醒的疫苗");
            return;
        }

        List<Long> vaccineIds = pendingVaccines.stream()
                .map(BabyVaccine::getVaccineId)
                .distinct()
                .collect(Collectors.toList());
        List<Vaccine> vaccines = vaccineMapper.selectBatchIds(vaccineIds);
        Map<Long, Vaccine> vaccineMap = vaccines.stream()
                .collect(Collectors.toMap(Vaccine::getId, v -> v));

        List<Long> babyIds = pendingVaccines.stream()
                .map(BabyVaccine::getBabyId)
                .distinct()
                .collect(Collectors.toList());
        List<Baby> babies = babyMapper.selectBatchIds(babyIds);
        Map<Long, Baby> babyMap = babies.stream()
                .collect(Collectors.toMap(Baby::getId, b -> b));

        int remindedCount = 0;

        for (BabyVaccine bv : pendingVaccines) {
            try {
                if (bv.getPlannedDate() == null || bv.getRemindDaysBefore() == null) {
                    continue;
                }

                long daysUntil = ChronoUnit.DAYS.between(today, bv.getPlannedDate());

                if (daysUntil <= bv.getRemindDaysBefore()) {
                    Vaccine vaccine = vaccineMap.get(bv.getVaccineId());
                    Baby baby = babyMap.get(bv.getBabyId());

                    String vaccineName = vaccine != null ? vaccine.getName() : "疫苗";
                    String babyName = baby != null ? baby.getName() : "宝宝";
                    String shortName = vaccine != null ? vaccine.getShortName() : "";

                    String title;
                    String content;

                    if (daysUntil == 0) {
                        title = "今日疫苗接种提醒";
                        content = babyName + "今天需要接种" + vaccineName + "（第" + bv.getDoseNumber() + "剂），请准时带宝宝前往接种。";
                    } else if (daysUntil > 0) {
                        title = "疫苗接种提醒";
                        content = babyName + "还有" + daysUntil + "天需要接种" + vaccineName + "（第" + bv.getDoseNumber() + "剂），请提前做好准备。";
                    } else {
                        title = "疫苗接种逾期提醒";
                        content = babyName + "的" + vaccineName + "（第" + bv.getDoseNumber() + "剂）已逾期" + Math.abs(daysUntil) + "天，请尽快安排接种。";
                    }

                    Long familyId = baby != null ? 1L : 1L;
                    pushService.pushToFamily(familyId, title, content);

                    bv.setReminded(1);
                    bv.setUpdateTime(LocalDateTime.now());
                    babyVaccineMapper.updateById(bv);

                    remindedCount++;
                }
            } catch (Exception e) {
                log.error("处理疫苗提醒失败，babyVaccineId={}", bv.getId(), e);
            }
        }

        log.info("疫苗接种提醒检查完成，共推送 {} 条提醒", remindedCount);
    }
}
