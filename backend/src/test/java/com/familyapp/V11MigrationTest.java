package com.familyapp;

import com.familyapp.calendar.CalendarEvent;
import com.familyapp.calendar.CalendarEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V11 迁移脚本验证测试（H2 兼容模式，Change 13）
 *
 * <p>H2 测试环境说明：
 * <ul>
 *   <li>ddl-auto=create-drop 下 Hibernate 先从 CalendarEvent 实体建 calendar_events 表，
 *       实体建表不含 CHECK 约束，导致 V11 的 CREATE TABLE IF NOT EXISTS 幂等跳过、约束不落地。</li>
 *   <li>因此约束类用例（用例3/4/5/6）先 DROP 再重放 V11 脚本，让建表语句真正执行、约束真实生效；
 *       裸 insert 须补齐 is_active/reminder_enabled/created_at/updated_at（Hibernate 建的表无 DB DEFAULT）。</li>
 *   <li>H2 MODE=PostgreSQL 已实测支持 ~ 正则运算符（非法格式被 CHECK 拒绝）。</li>
 * </ul>
 *
 * <p>用例5/6（审查修复 P1/I-2 的必加验收）：走 JPA Repository 真实写路径
 * 验证一次性日程的 recurrence 落成真正的 SQL NULL，使 chk_ce_date_recurrence
 * 在写侧不再被 JSON 'null' 字面量撞破——这正是修复前生产 PG 上的故障场景。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@Sql(scripts = {
    "classpath:db/v6-test-setup.sql",
    "classpath:db/migration/V11__Add_Calendar_Events.sql"
})
class V11MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CalendarEventRepository calendarEventRepository;

    @Test
    void 用例1_calendar_events表创建成功() {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME) = 'calendar_events'",
            Integer.class);
        assertEquals(1, count);
    }

    @Test
    void 用例2_tasks表新增due_time和source_event_id列() {
        Integer dueTimeCol = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'tasks' AND LOWER(COLUMN_NAME) = 'due_time'",
            Integer.class);
        assertEquals(1, dueTimeCol);
        Integer sourceEventCol = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE LOWER(TABLE_NAME) = 'tasks' AND LOWER(COLUMN_NAME) = 'source_event_id'",
            Integer.class);
        assertEquals(1, sourceEventCol);
    }

    @Test
    void 用例3_互斥约束_一次性与重复不能同时设置() {
        重建表使V11约束生效();
        // event_date 和 recurrence 同时非空应违反约束
        assertThrows(Exception.class, () -> 插入日程("10:00", "'2026-09-10'", "'{\"freq\":\"daily\"}'"),
            "event_date 与 recurrence 同时非空应被 chk_ce_date_recurrence 拒绝");
        // 两者皆 NULL 同样违反约束（一次性与重复必须二选一）
        assertThrows(Exception.class, () -> 插入日程("10:00", "NULL", "NULL"),
            "event_date 与 recurrence 均为 NULL 应被 chk_ce_date_recurrence 拒绝");
    }

    @Test
    void 用例4_时间格式约束() {
        重建表使V11约束生效();
        // 正则 ^\d{2}:\d{2}$ 只校验「两位数字:两位数字」形状（'25:99' 符合形状会被放行），
        // 故用真正违反形状的值验证约束
        assertThrows(Exception.class, () -> 插入日程("abc", "'2026-09-10'", "NULL"),
            "非数字时间应被 chk_ce_time_format 拒绝");
        assertThrows(Exception.class, () -> 插入日程("9:00", "'2026-09-10'", "NULL"),
            "单位数小时应被 chk_ce_time_format 拒绝");
        // 正向对照：合法格式不应被误伤（一次性 + 重复各一条）
        assertEquals(1, 插入日程("10:00", "'2026-09-10'", "NULL"));
        assertEquals(1, 插入日程("08:30", "NULL", "'{\"freq\":\"daily\"}'"));
    }

    @Test
    void 用例5_Repository保存一次性事件后recurrence为真SQL_NULL() {
        重建表使V11约束生效();
        // 走 JPA 真实写路径（与生产同一条 SQL）：修复前 Hibernate 的 JSON 类型映射
        // 会把 Java null 写成 'null' 字面量，INSERT 直接撞 chk_ce_date_recurrence 违例
        CalendarEvent saved = calendarEventRepository.saveAndFlush(一次性事件("约束验收-新建"));
        assertTrue(recurrenceIsSqlNull(saved.getId()),
            "一次性日程经 Repository 落库后 recurrence 必须是真 SQL NULL");
    }

    @Test
    void 用例6_Repository更新一次性事件后recurrence仍为真SQL_NULL() {
        重建表使V11约束生效();
        CalendarEvent saved = calendarEventRepository.saveAndFlush(一次性事件("约束验收-更新前"));
        // 更新路径回归：脏实体回写不得把 recurrence 从 SQL NULL 变成 'null' 字面量
        CalendarEvent loaded = calendarEventRepository.findById(saved.getId()).orElseThrow();
        loaded.setTitle("约束验收-更新后");
        calendarEventRepository.saveAndFlush(loaded);
        assertTrue(recurrenceIsSqlNull(saved.getId()),
            "更新后 recurrence 必须仍为真 SQL NULL");
    }

    /** 构造一个 recurrence 为 Java null 的合法一次性事件（family id=1 由 v6-test-setup 种子保证） */
    private CalendarEvent 一次性事件(String title) {
        CalendarEvent event = new CalendarEvent();
        event.setFamilyId(1L);
        event.setTitle(title);
        event.setEventDate(LocalDate.of(2026, 9, 20));
        event.setEventTime("09:00");
        return event;
    }

    /** 直查库判断 recurrence 是否为真 SQL NULL（CASE 包装规避 H2/PG 返回类型差异） */
    private boolean recurrenceIsSqlNull(Long id) {
        Boolean isNull = jdbcTemplate.queryForObject(
            "SELECT CASE WHEN recurrence IS NULL THEN TRUE ELSE FALSE END " +
            "FROM calendar_events WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(isNull);
    }

    /**
     * DROP 掉 Hibernate 实体建出的无约束表，重放 V11 脚本让 CREATE TABLE 真正执行，
     * 两个 CHECK 约束（时间格式 ~ 正则 / 日期与重复规则互斥）真实落地。
     */
    private void 重建表使V11约束生效() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS calendar_events");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V11__Add_Calendar_Events.sql"))
            .execute(Objects.requireNonNull(jdbcTemplate.getDataSource()));
    }

    /** 完整列插入（Hibernate 建的表无 DB DEFAULT，须显式补 NOT NULL 列） */
    private int 插入日程(String eventTime, String dateLiteral, String recurrenceLiteral) {
        return jdbcTemplate.update(
            "INSERT INTO calendar_events (family_id, title, event_time, event_date, recurrence, " +
            "is_active, reminder_enabled, created_at, updated_at) " +
            "VALUES (1, '测试日程', ?, " + dateLiteral + ", " + recurrenceLiteral + ", TRUE, TRUE, NOW(), NOW())",
            eventTime);
    }
}
