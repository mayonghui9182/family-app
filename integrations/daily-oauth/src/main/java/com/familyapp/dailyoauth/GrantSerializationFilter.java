package com.familyapp.dailyoauth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/** 单实例串行修改授权，避免并发刷新在撤销后重新插入授权；不支持多副本发布。 */
final class GrantSerializationFilter extends OncePerRequestFilter {
    private final Object lock;
    GrantSerializationFilter(Object lock){this.lock=lock;}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
        throws IOException,ServletException {
        String path=req.getServletPath();
        if(path.equals("/oauth2/token") || path.equals("/oauth2/authorize") || path.equals("/oauth2/revoke")
            || path.equals("/authorizations/revoke")) {
            synchronized(lock){chain.doFilter(req,res);}
        } else chain.doFilter(req,res);
    }
}
