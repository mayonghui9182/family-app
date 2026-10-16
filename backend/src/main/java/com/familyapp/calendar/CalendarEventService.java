package com.familyapp.calendar;

import com.familyapp.auth.UserContext;
import com.familyapp.calendar.dto.CalendarEventResponse;
import com.familyapp.calendar.dto.CreateCalendarEventRequest;
import com.familyapp.calendar.dto.TodayEventItem;
import com.familyapp.calendar.dto.UpdateCalendarEventRequest;
import com.familyapp.common.ResultException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 日程服务（Change 13）：CRUD + 校验 + 今日展开。
 *
 * <p>设计要点：
 * <ul>
 *   <li>所有查询/写入均按 {@code UserContext.current().familyId()} 做家庭隔离</li>
 *   <li>CHILD 角色禁止写操作（40302）</li>
 *   <li>校验码：47001 标题为空 / 47002 时间格式 / 47003 日期与重复规则未二选一 /
 *       47004 重复规则格式 / 47005 日程不存在</li>
 *   <li>recurrence 请求侧为 JSON 字符串，实体侧为 JsonNode（JSONB）</li>
 *   <li>删除为软删（设置 deletedAt）</li>
 * </ul>
 */
@Service
@Transactional
public class CalendarEventService {

    /** 时间点格式：两位小时:两位分钟（与 V11 chk_ce_time_format 同形状） */
    private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}$");

    /** 重复频率白名单：与前端 UI 的三档一一对应，超出即视为无效规则（47004）而非静默永不触发 */
    private static final Set<String> SUPPORTED_FREQ = Set.of("daily", "weekly", "monthly");

    /** weekly 缺 weekdays / 空数组 / 含非法元素的统一提示语（两处复用，避免文案漂移） */
    private static final String WEEKLY_HINT = "每周重复请至少选择一天（周一至周日）";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CalendarEventRepository repository;
    private final Clock clock;
    /**
     * 重复规则匹配器（Task 5 提取为共用组件）：无状态，此处直接实例化复用同一份逻辑，
     * 保持 {@code (repository, clock)} 构造签名不变，使既有服务测试零改动。
     */
    private final CalendarRecurrenceMatcher matcher = new CalendarRecurrenceMatcher();

    public CalendarEventService(CalendarEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 创建日程：权限 → 家庭上下文 → 校验 → 解析重复规则 → 保存 */
    public CalendarEventResponse create(CreateCalendarEventRequest req) {
        UserContext.CurrentUser user = UserContext.current();
        checkWriteAccess(user.role());
        Long familyId = requireFamilyId(user.familyId());
        validateTitle(req.getTitle());
        validateEventTime(req.getEventTime());
        validateDateRecurrence(req.getEventDate(), req.getRecurrence());

        CalendarEvent event = new CalendarEvent();
        event.setFamilyId(familyId);
        event.setCreatedBy(user.userId());
        event.setTitle(req.getTitle());
        event.setDescription(req.getDescription());
        event.setEventDate(req.getEventDate());
        event.setEventTime(req.getEventTime());
        event.setRecurrence(isBlank(req.getRecurrence()) ? null : parseRecurrence(req.getRecurrence()));
        if (req.getReminderEnabled() != null) {
            event.setReminderEnabled(req.getReminderEnabled());
        }
        event.setAssigneeProfileId(req.getAssigneeProfileId());
        event.setCategoryId(req.getCategoryId());
        return CalendarEventResponse.fromEntity(repository.save(event));
    }

    /** 更新日程（PATCH 语义）：仅更新请求中非空字段，更新后仍须满足日期/重复规则二选一 */
    public CalendarEventResponse update(Long id, UpdateCalendarEventRequest req) {
        UserContext.CurrentUser user = UserContext.current();
        checkWriteAccess(user.role());
        CalendarEvent event = findOwnedEvent(id, requireFamilyId(user.familyId()));

        // 空串/纯空白的 recurrence 与 create 同口径视为「未提供」，否则会被解析为 null 并把
        // 日期一并清空，最终撞库约束 chk_ce_date_recurrence 变成 500
        String recurrencePatch = isBlank(req.getRecurrence()) ? null : req.getRecurrence();
        if (req.getTitle() != null) {
            validateTitle(req.getTitle());
            event.setTitle(req.getTitle());
        }
        if (req.getEventTime() != null) {
            validateEventTime(req.getEventTime());
            event.setEventTime(req.getEventTime());
        }
        if (req.getEventDate() != null && recurrencePatch != null) {
            // 与 create 同口径：一次请求不允许同时指定一次性日期与重复规则
            throw new ResultException(47003, "一次性日期与重复规则不能同时设置");
        }
        if (req.getEventDate() != null) {
            // 切换为一次性事件：清空重复规则
            event.setEventDate(req.getEventDate());
            event.setRecurrence(null);
        }
        if (recurrencePatch != null) {
            // 切换为重复事件：清空一次性日期
            event.setRecurrence(parseRecurrence(recurrencePatch));
            event.setEventDate(null);
        }
        if (req.getDescription() != null) {
            event.setDescription(req.getDescription());
        }
        if (req.getReminderEnabled() != null) {
            event.setReminderEnabled(req.getReminderEnabled());
        }
        if (req.getAssigneeProfileId() != null) {
            event.setAssigneeProfileId(req.getAssigneeProfileId());
        }
        if (req.getCategoryId() != null) {
            event.setCategoryId(req.getCategoryId());
        }
        return CalendarEventResponse.fromEntity(repository.save(event));
    }

    /** 软删除：设置 deletedAt（@Where 过滤后对普通查询不可见） */
    public void delete(Long id) {
        UserContext.CurrentUser user = UserContext.current();
        checkWriteAccess(user.role());
        CalendarEvent event = findOwnedEvent(id, requireFamilyId(user.familyId()));
        event.setDeletedAt(Instant.now(clock));
        repository.save(event);
    }

    /** 列表：按家庭查未删除日程；includeInactive=false 时只返回启用中的 */
    @Transactional(readOnly = true)
    public List<CalendarEventResponse> list(boolean includeInactive) {
        Long familyId = requireFamilyId(UserContext.current().familyId());
        List<CalendarEvent> events = includeInactive
                ? repository.findByFamilyIdAndDeletedAtIsNull(familyId)
                : repository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(familyId);
        return events.stream().map(CalendarEventResponse::fromEntity).toList();
    }

    /** 详情：按 id + familyId 查 */
    @Transactional(readOnly = true)
    public CalendarEventResponse get(Long id) {
        Long familyId = requireFamilyId(UserContext.current().familyId());
        return CalendarEventResponse.fromEntity(findOwnedEvent(id, familyId));
    }

    /**
     * 今日日程展开：查本家庭启用中的日程，一次性事件看 eventDate==今日，
     * 重复事件按 recurrence 匹配今日；结果按 eventTime 升序。
     */
    @Transactional(readOnly = true)
    public List<TodayEventItem> todayEvents() {
        Long familyId = requireFamilyId(UserContext.current().familyId());
        LocalDate today = LocalDate.now(clock);
        return repository.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(familyId).stream()
                .filter(e -> matcher.matchesToday(e, today))
                .sorted(Comparator.comparing(CalendarEvent::getEventTime))
                .map(e -> new TodayEventItem(e.getId(), e.getTitle(), e.getEventTime(), e.getAssigneeProfileId()))
                .toList();
    }

    /** CHILD 角色禁止创建/修改/删除日程 */
    private void checkWriteAccess(String role) {
        if ("CHILD".equals(role)) {
            throw new ResultException(40302, "孩子成员不能创建或修改日程");
        }
    }

    /**
     * 家庭上下文守卫（审查修复 I-1）：familyId 缺失时快速失败，禁止拿 null 触库。
     *
     * <p>对齐既有先例（TaskServiceImpl / CategoryServiceImpl 的 FAMILY_HEADER_REQUIRED 40041）；
     * 文案面向老年用户、不出现「请求头」等技术术语。缺了这层守卫，
     * 硬约束 4（family_id 多租户隔离）会在上下文异常时静默退化为 null 作用域查询。
     */
    private Long requireFamilyId(Long familyId) {
        if (familyId == null) {
            throw new ResultException(40041, "请先选择家庭，再进行日程操作");
        }
        return familyId;
    }

    /** 按 id + familyId 查未删除日程，不存在 → 47005 */
    private CalendarEvent findOwnedEvent(Long id, Long familyId) {
        return repository.findByIdAndFamilyIdAndDeletedAtIsNull(id, familyId)
                .orElseThrow(() -> new ResultException(47005, "日程不存在"));
    }

    private void validateTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new ResultException(47001, "日程标题不能为空");
        }
    }

    private void validateEventTime(String eventTime) {
        if (eventTime == null || eventTime.isBlank() || !TIME_PATTERN.matcher(eventTime).matches()) {
            throw new ResultException(47002, "日程时间格式不正确");
        }
    }

    /** 一次性日期与重复规则必须二选一（DB chk_ce_date_recurrence 的服务层镜像） */
    private void validateDateRecurrence(LocalDate eventDate, String recurrence) {
        boolean hasDate = eventDate != null;
        boolean hasRecurrence = !isBlank(recurrence);
        if (hasDate && hasRecurrence) {
            throw new ResultException(47003, "一次性日期与重复规则不能同时设置");
        }
        if (!hasDate && !hasRecurrence) {
            throw new ResultException(47003, "请填写日程日期，或选择重复规则（每天/每周/每月）");
        }
    }

    /**
     * 解析并校验重复规则 JSON 字符串，任何不合法一律 47004；调用方保证非空白。
     *
     * <p>分两层：先语法（必须是合法 JSON object），再取值白名单 {@link #validateRecurrenceValues}。
     * 取值层是 Change 13 全分支终审 IMP-2 的附带项：修复前只查语法，{@code {"freq":"yearly"}}
     * 与 {@code {"freq":"monthly","day":99}} 都会「创建成功」，而匹配器对它们恒不命中——
     * 用户以为设好了每月提醒，实际往后任何一天都不会有事发生，且界面上看不出异常。
     *
     * <p>校验只加在写入口（create / update），读路径刻意保持前向兼容：库里已存在的未知 freq
     * 仍由匹配器静默不命中（见 today_events_ignores_unknown_recurrence_freq），
     * 这样将来新增频率时不会因为旧数据把日程列表与提醒任务整体打挂。
     */
    private JsonNode parseRecurrence(String recurrence) {
        JsonNode node;
        try {
            node = MAPPER.readTree(recurrence);
        } catch (Exception e) {
            throw new ResultException(47004, "重复规则格式不正确");
        }
        if (node == null || !node.isObject()) {
            throw new ResultException(47004, "重复规则格式不正确");
        }
        validateRecurrenceValues(node);
        return node;
    }

    /**
     * 取值白名单：freq ∈ {daily, weekly, monthly}；monthly 须带整数 day ∈ [1,31]；
     * weekly 须带非空数组 weekdays 且元素为整数 ∈ [1,7]（ISO 星期，1=周一、7=周日）。
     * 提示语按硬约束「老人易用」写：说清该选什么，而不是报字段名或 JSON 术语。
     */
    private void validateRecurrenceValues(JsonNode node) {
        JsonNode freq = node.get("freq");
        if (freq == null || !freq.isTextual() || !SUPPORTED_FREQ.contains(freq.asText())) {
            throw new ResultException(47004, "重复规则不正确，请选择每天、每周或每月");
        }
        switch (freq.asText()) {
            case "monthly" -> {
                if (!isIntInRange(node.get("day"), 1, 31)) {
                    throw new ResultException(47004, "每月重复请选择 1 到 31 之间的几号");
                }
            }
            case "weekly" -> {
                JsonNode weekdays = node.get("weekdays");
                // 空数组等价于「每周哪天都不提醒」，是误操作而非有效规则，一并拒掉
                if (weekdays == null || !weekdays.isArray() || weekdays.isEmpty()) {
                    throw new ResultException(47004, WEEKLY_HINT);
                }
                for (JsonNode day : weekdays) {
                    if (!isIntInRange(day, 1, 7)) {
                        throw new ResultException(47004, WEEKLY_HINT);
                    }
                }
            }
            // daily 无附加字段
            default -> {
            }
        }
    }

    /** 是否为闭区间 [min,max] 内的整数：字符串、小数、null 与超出 int 位宽的大整数一律不通过。 */
    private static boolean isIntInRange(JsonNode node, int min, int max) {
        if (node == null || node.isNull() || !node.isIntegralNumber()) {
            return false;
        }
        // 用 BigInteger 比较而非 intValue()：后者对超长整数会回绕，可能把 4294967326 误判成 30
        BigInteger value = node.bigIntegerValue();
        return value.compareTo(BigInteger.valueOf(min)) >= 0 && value.compareTo(BigInteger.valueOf(max)) <= 0;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
