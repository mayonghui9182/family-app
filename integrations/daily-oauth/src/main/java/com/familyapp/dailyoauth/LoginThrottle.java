package com.familyapp.dailyoauth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.filter.OncePerRequestFilter;

/** 单实例登录限流；不信任转发头，不记录或返回邮箱密码。 */
final class LoginThrottle extends OncePerRequestFilter {
    private final ConcurrentHashMap<String,Window> attempts=new ConcurrentHashMap<>();
    private record Window(long minute,int count){}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)
        throws IOException,ServletException {
        if(req.getServletPath().equals("/login") && req.getMethod().equals("POST")) {
            long minute=System.currentTimeMillis()/60000;
            attempts.entrySet().removeIf(entry->entry.getValue().minute()!=minute);
            String username=req.getParameter("username");
            if(username==null || username.length()>200 || attempts.size()>10000) {res.setStatus(429);return;}
            String key=java.util.HexFormat.of().formatHex(digest(username.toLowerCase(java.util.Locale.ROOT)));
            Window value=attempts.compute(key,(ignored,old)->
                new Window(minute,old==null || old.minute()!=minute?1:old.count()+1));
            if(value.count()>10){res.setStatus(429);res.setHeader("Retry-After","60");return;}
        }
        chain.doFilter(req,res);
    }
    private byte[] digest(String input) {
        try{return java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException("限流初始化失败");}
    }
}
