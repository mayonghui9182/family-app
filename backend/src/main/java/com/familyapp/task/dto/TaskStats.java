package com.familyapp.task.dto;

/**
 * 首页任务统计响应。
 * <p>4 个数字：家庭待办、本人待办、家庭逾期、本人逾期。
 * 老人友好：点击卡片可跳转任务列表（前端带 query 参数筛选）。</p>
 */
public record TaskStats(
    long familyPendingCount,
    long minePendingCount,
    long familyOverdueCount,
    long mineOverdueCount
) {}
