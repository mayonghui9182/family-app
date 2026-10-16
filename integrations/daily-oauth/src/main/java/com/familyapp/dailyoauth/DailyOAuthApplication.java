package com.familyapp.dailyoauth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立授权服务，不加载主应用、不签发主应用登录令牌。 */
@SpringBootApplication
public class DailyOAuthApplication {
    public static void main(String[] args) {
        if(!"true".equals(System.getenv("DAILY_OAUTH_ENABLED")))
            throw new IllegalStateException("每日授权服务器尚未显式启用");
        for(String key:new String[]{"DAILY_OAUTH_DATABASE_URL","DAILY_OAUTH_DATABASE_USER","DAILY_OAUTH_DATABASE_PASSWORD",
            "DAILY_OAUTH_CHATGPT_CLIENT_ID","DAILY_OAUTH_CHATGPT_CLIENT_SECRET","DAILY_OAUTH_CHATGPT_REDIRECT_URI",
            "DAILY_OAUTH_READER_ID","DAILY_OAUTH_READER_SECRET"}) {
            String value=System.getenv(key);
            if(value==null || value.isBlank())throw new IllegalStateException("授权配置缺失："+key);
        }
        SpringApplication.run(DailyOAuthApplication.class,args);
    }
}
