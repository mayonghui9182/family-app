package com.familyapp.dailyoauth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 严格绑定授权与兑换请求的资源，不影响框架处理的同意表单。 */
final class ResourceParameterFilter extends OncePerRequestFilter {
    private final String resource;
    ResourceParameterFilter(String resource){this.resource=resource;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
        throws IOException,ServletException {
        String path=req.getServletPath();
        boolean authorization=path.equals("/oauth2/authorize") && req.getMethod().equals("GET");
        boolean exchange=path.equals("/oauth2/token") && java.util.Set.of("authorization_code","refresh_token")
            .contains(req.getParameter("grant_type")==null?"":req.getParameter("grant_type"));
        String[] values=req.getParameterValues("resource");
        if((authorization || exchange) && (values==null || values.length!=1 || !resource.equals(values[0]))) {
            res.setStatus(400);res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"invalid_target\"}");return;
        }
        if(authorization && !"S256".equals(req.getParameter("code_challenge_method"))) {
            res.setStatus(400);res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"invalid_request\"}");return;
        }
        chain.doFilter(req,res);
    }
}
