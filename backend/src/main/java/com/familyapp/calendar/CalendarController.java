package com.familyapp.calendar;

import com.familyapp.calendar.dto.CalendarEventResponse;
import com.familyapp.calendar.dto.CreateCalendarEventRequest;
import com.familyapp.calendar.dto.TodayEventItem;
import com.familyapp.calendar.dto.UpdateCalendarEventRequest;
import com.familyapp.common.Result;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 日程管理 API（Change 13）：E-1~E-6 六个端点。
 *
 * <p>口径与 {@code TaskController} 一致：本层不做鉴权，登录态与家庭上下文由
 * {@code AuthInterceptor} + {@code FamilyContextInterceptor} 写入 {@code UserContext}，
 * 家庭隔离（硬约束 4）与 CHILD 只读（40302）在 {@code CalendarEventService} 内统一收口。
 *
 * <p>请求体必须挂 {@code @Valid}：DTO 上的 {@code @NotBlank/@Pattern/@Size} 才生效，
 * 校验失败由 {@code GlobalExceptionHandler} 转 400 + 40099 + DTO 中文文案。
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    private final CalendarEventService service;

    public CalendarController(CalendarEventService service) {
        this.service = service;
    }

    /** E-1 日程列表；includeInactive=true 时一并返回已停用的日程 */
    @GetMapping("/events")
    public Result<List<CalendarEventResponse>> list(
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return Result.ok(service.list(includeInactive));
    }

    /** E-2 新建日程 */
    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<CalendarEventResponse> create(@Valid @RequestBody CreateCalendarEventRequest req) {
        return Result.ok(service.create(req));
    }

    /** E-3 日程详情 */
    @GetMapping("/events/{id}")
    public Result<CalendarEventResponse> get(@PathVariable Long id) {
        return Result.ok(service.get(id));
    }

    /** E-4 更新日程（PATCH 语义：仅非空字段被更新） */
    @PutMapping("/events/{id}")
    public Result<CalendarEventResponse> update(@PathVariable Long id,
                                                @Valid @RequestBody UpdateCalendarEventRequest req) {
        return Result.ok(service.update(id, req));
    }

    /** E-5 删除日程（软删） */
    @DeleteMapping("/events/{id}")
    public Result<Boolean> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok(true);
    }

    /** E-6 今日日程展开（一次性看日期命中，重复按规则命中，按时间升序） */
    @GetMapping("/today")
    public Result<List<TodayEventItem>> today() {
        return Result.ok(service.todayEvents());
    }
}
