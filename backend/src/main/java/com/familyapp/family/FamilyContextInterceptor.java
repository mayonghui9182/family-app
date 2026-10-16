package com.familyapp.family;

import com.familyapp.auth.UserContext;
import com.familyapp.common.ApiException;
import com.familyapp.common.ResultCode;
import com.familyapp.family.FamilyMemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

@Component
public class FamilyContextInterceptor implements HandlerInterceptor {

    private final FamilyMemberRepository memberRepo;

    public FamilyContextInterceptor(FamilyMemberRepository memberRepo) {
        this.memberRepo = memberRepo;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        // Change 6 扩展：/api/agent/v1/** 公开代理端点也需要 X-Family-Id 解析与成员校验
        // person-profiles 接口同样需要家庭上下文（familyId 多租户隔离）
        // Change 13 扩展：日程端点同理——AuthInterceptor 阶段 familyId/role 均为 null，
        // 不在本白名单内会导致服务层的家庭隔离（硬约束 4）与 CHILD 只读判定（40302）静默失效。
        // 审查修复 M-1：calendar 收紧为「精确 /api/calendar 或 /api/calendar/ 前缀」双条件，
        // 避免 startsWith("/api/calendar") 误伤 /api/calendar-events 这类兄弟前缀路径
        boolean calendarHit = path.equals("/api/calendar") || path.startsWith("/api/calendar/");
        if (!(path.startsWith("/api/tasks")
                || path.startsWith("/api/categories")
                || calendarHit
                || path.startsWith("/api/v1/person-profiles")
                || path.startsWith("/api/agent"))) {
            return true;
        }
        UserContext.CurrentUser curr = safeCurrent();
        if (curr == null) return true;
        String header = request.getHeader("X-Family-Id");
        if (header == null || header.isBlank()) return true;
        Long familyId;
        try {
            familyId = Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(ResultCode.FAMILY_HEADER_REQUIRED, "X-Family-Id 格式错误，必须是数字");
        }
        Optional<FamilyMember> mOpt = memberRepo.findByFamilyIdAndUserIdAndLeftAtIsNull(familyId, curr.userId());
        if (mOpt.isEmpty()) {
            throw new ApiException(ResultCode.FAMILY_NOT_MEMBER);
        }
        FamilyMember m = mOpt.get();
        UserContext.setCurrent(new UserContext.CurrentUser(
                curr.userId(), curr.email(), curr.nickname(), familyId, m.getRole().name()
        ));
        return true;
    }

    private UserContext.CurrentUser safeCurrent() {
        try { return UserContext.current(); }
        catch (ApiException e) { return null; }
    }
}