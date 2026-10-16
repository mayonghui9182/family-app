package com.familyapp.dailyoauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.HtmlUtils;

/** 简单中文登录和只读同意页，所有外部输入 HTML 转义，表单携带 CSRF。 */
@Controller
class BrowserController {
    private final String issuer;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations;
    private final org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService consents;
    BrowserController(org.springframework.jdbc.core.JdbcTemplate jdbc,
        org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService authorizations,
        org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService consents,
        @org.springframework.beans.factory.annotation.Value("${daily.issuer}") String issuer) {
        this.jdbc=jdbc;this.authorizations=authorizations;this.consents=consents;this.issuer=issuer;
    }
    private String escape(String value){return HtmlUtils.htmlEscape(value==null?"":value);}
    private String hidden(String name,String value){return "<input type=hidden name=\""+escape(name)+"\" value=\""+escape(value)+"\">";}
    private String csrf(HttpServletRequest request) {
        var token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        return token==null?"":hidden(token.getParameterName(),token.getToken());
    }
    private String page(String title,String body){return "<!doctype html><html lang=zh-CN><meta charset=utf-8>"
        +"<meta name=viewport content=\"width=device-width, initial-scale=1\"><title>"+title+"</title>"
        +"<style>body{font:20px sans-serif;max-width:560px;margin:32px auto;padding:20px;color:#17212b;background:#fff}"
        +"input,button{font:inherit;box-sizing:border-box;max-width:100%;padding:12px;margin:12px 0}label{display:block}"
        +"button{min-height:48px}a{color:#164a95}</style><h1>"+title+"</h1>"+body+"</html>";}

    @GetMapping(value="/authorizations",produces="text/html;charset=UTF-8") @ResponseBody
    String authorizations(HttpServletRequest request,java.security.Principal principal) {
        StringBuilder body=new StringBuilder("<p>撤销后，ChatGPT 将无法继续读取你的每日记录。</p>");
        var ids=jdbc.queryForList("SELECT id FROM oauth2_authorization WHERE principal_name=? AND authorized_scopes=?",
            String.class,principal.getName(),"daily:read");
        if(ids.isEmpty())body.append("<p>没有已授权的每日记录连接。</p>");
        for(String id:ids)body.append("<form method=post action=\"").append(escape(issuer))
            .append("/authorizations/revoke\">").append(csrf(request)).append(hidden("id",id))
            .append("<button type=submit>撤销每日记录授权</button></form>");
        return page("管理我的授权",body.toString());
    }
    @PostMapping("/authorizations/revoke")
    @org.springframework.transaction.annotation.Transactional
    String revoke(@RequestParam("id") String id,java.security.Principal principal) {
        var authorization=authorizations.findById(id);
        if(authorization!=null && authorization.getPrincipalName().equals(principal.getName())) {
            var ids=jdbc.queryForList("SELECT id FROM oauth2_authorization WHERE registered_client_id=? AND principal_name=?",
                String.class,authorization.getRegisteredClientId(),principal.getName());
            for(String grantId:ids) {
                var grant=authorizations.findById(grantId);
                if(grant!=null)authorizations.remove(grant);
            }
            var consent=consents.findById(authorization.getRegisteredClientId(),principal.getName());
            if(consent!=null)consents.remove(consent);
        }
        return "redirect:/authorizations";
    }

    @GetMapping(value="/login",produces="text/html;charset=UTF-8") @ResponseBody
    String login(HttpServletRequest request) {
        return page("家庭应用授权登录",(request.getParameter("error")==null?"":"<p role=alert>登录失败，请检查邮箱和密码。</p>")
            +"<p>使用家庭应用的邮箱和密码。登录后仍需确认授权范围。</p><form method=post action=\""
            +escape(issuer)+"/login\">"+csrf(request)
            +"<label>邮箱<input name=username type=email autocomplete=username required></label>"
            +"<label>密码<input name=password type=password autocomplete=current-password required></label>"
            +"<button type=submit>登录</button></form>");
    }

    @GetMapping(value="/consent",produces="text/html;charset=UTF-8") @ResponseBody
    String consent(HttpServletRequest request) {
        return page("允许 ChatGPT 读取每日记录？","<p>允许读取本人的每日计划和复盘中的任务标题、重点和状态。"
            +"数据可能包含私人工作事项。不会允许修改任务，也不会读取复盘自由文本反馈。</p>"
            +"<form method=post action=\""+escape(issuer)+"/oauth2/authorize\">"
            +csrf(request)+hidden("client_id",request.getParameter("client_id"))+hidden("state",request.getParameter("state"))
            +"<label><input type=checkbox name=scope value=\"daily:read\">我同意读取上述记录</label>"
            +"<button type=submit>确认选择</button></form><p>不勾选并确认即可拒绝授权。</p>");
    }
}
