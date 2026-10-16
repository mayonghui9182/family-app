package com.familyapp.daily;

import com.familyapp.common.*;
import com.fasterxml.jackson.databind.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 仅接受本轮随机测试库，验证真实 PostgreSQL 迁移、并发和安全逆向。 */
@EnabledIfEnvironmentVariable(named="FAMILYAPP_DAILY_POSTGRES_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/familyapp_daily_[a-f0-9]+_test")
class DailyPostgresTest {
    @Test void migration_concurrency_and_safe_rollback() throws Exception {
        String url=System.getenv("FAMILYAPP_DAILY_POSTGRES_URL");
        String user=System.getenv("FAMILYAPP_DAILY_POSTGRES_USER");
        String password=System.getenv("FAMILYAPP_DAILY_POSTGRES_PASSWORD");
        var flyway=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load();
        flyway.migrate();
        var db=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        db.update("INSERT INTO users(id,email,nickname,password_hash) VALUES (9001,'daily1@example.test','测试一','仅隔离测试'),(9002,'daily2@example.test','测试二','仅隔离测试')");
        ObjectMapper json=new ObjectMapper();var sources=mock(DailySources.class);
        var items=json.createArrayNode();items.addObject().put("key","work:test").put("title","隔离测试任务")
                .put("status","PENDING").put("priority","HIGH");
        when(sources.collect(anyLong(),any())).thenReturn(items);
        var service=new DailyService(db,json,sources,Clock.fixed(Instant.parse("2026-10-10T00:30:00Z"),ZoneOffset.UTC),true);
        var pool=Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> generation=new ArrayList<>();
            for(int n=0;n<8;n++)generation.add(()->{service.generate(9001L,"PLAN");return true;});
            for(var future:pool.invokeAll(generation))assertTrue(future.get(10,TimeUnit.SECONDS));
            assertEquals(1,db.queryForObject("SELECT count(*) FROM daily_records WHERE user_id=9001",Integer.class));
            List<Callable<Integer>> confirmation=new ArrayList<>();
            for(int n=0;n<8;n++)confirmation.add(()->{
                try{service.confirm(9001L,LocalDate.of(2026,10,10),0);return 1;}
                catch(ApiException ex){assertEquals(ResultCode.DAILY_VERSION_CONFLICT,ex.getCode());return 0;}
            });
            int winners=0;for(var future:pool.invokeAll(confirmation))winners+=future.get(10,TimeUnit.SECONDS);
            assertEquals(1,winners);
        } finally {pool.shutdownNow();}
        assertTrue(service.read(9002L,LocalDate.of(2026,10,10),"PLAN").path("missing").asBoolean());
        String rollback=new String(new ClassPathResource("db/rollback/V17__Personal_Daily_Planning.sql")
                .getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertThrows(Exception.class,()->db.execute(rollback));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM daily_records WHERE user_id=9001",Integer.class));
        db.update("DELETE FROM daily_records WHERE user_id=9001");db.execute(rollback);
        assertEquals(0,db.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='daily_records'",Integer.class));
        flyway.migrate();
        assertEquals(0,db.queryForObject("SELECT count(*) FROM daily_records",Integer.class));
    }
}
