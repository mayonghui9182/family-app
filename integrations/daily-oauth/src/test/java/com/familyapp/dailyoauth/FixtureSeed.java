package com.familyapp.dailyoauth;

import java.sql.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 只允许随机隔离容器库；用于跨服务端到端测试的虚构账号。 */
class FixtureSeed {
    public static void main(String[] args) throws Exception {
        String url=System.getenv("DAILY_OAUTH_DATABASE_URL");
        if(url==null || !url.matches("jdbc:postgresql://familyapp-oauth-e2e-[a-f0-9]+-pg:5432/familyapp_oauth_it_[a-f0-9]+"))
            throw new IllegalArgumentException("仅允许随机测试库");
        try(var db=DriverManager.getConnection(url,"tester",System.getenv("DAILY_OAUTH_DATABASE_PASSWORD"))) {
            db.createStatement().execute("CREATE SCHEMA daily_oauth");
            db.createStatement().execute("CREATE TABLE public.users(id BIGINT PRIMARY KEY,email VARCHAR(100),password_hash VARCHAR(100),deleted_at TIMESTAMP)");
            try(var insert=db.prepareStatement("INSERT INTO public.users VALUES(?,?,?,NULL)")) {
                for(int id=1;id<=2;id++) {
                    insert.setInt(1,id);insert.setString(2,"user"+id+"@example.test");
                    insert.setString(3,new BCryptPasswordEncoder().encode("fixture-password-123"));insert.executeUpdate();
                }
            }
            String restricted=System.getenv("DAILY_OAUTH_RESTRICTED_PASSWORD");
            if(restricted==null || restricted.length()<32)throw new IllegalArgumentException("测试角色凭据缺失");
            try(var format=db.prepareStatement("SELECT format('CREATE ROLE oauth_fixture LOGIN PASSWORD %L',?)")) {
                format.setString(1,restricted);
                try(var result=format.executeQuery()){result.next();db.createStatement().execute(result.getString(1));}
            }
            db.createStatement().execute("ALTER SCHEMA daily_oauth OWNER TO oauth_fixture");
            db.createStatement().execute("GRANT USAGE ON SCHEMA public TO oauth_fixture");
            db.createStatement().execute("GRANT SELECT(id,email,password_hash,deleted_at) ON public.users TO oauth_fixture");
        }
    }
}
