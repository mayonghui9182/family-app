package com.familyapp.daily;

import com.familyapp.common.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 真实成员、档案指派和日程查询结合隔离工作平台桩验证聚合权限。 */
@SpringBootTest(properties={"daily.enabled=true","wechat.notify-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:dailysources;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
@ActiveProfiles("auth-test")
class DailySourcesTest {
    static HttpServer platform;
    static AtomicBoolean failure=new AtomicBoolean();
    static java.util.concurrent.atomic.AtomicReference<String> ownerOverride=new java.util.concurrent.atomic.AtomicReference<>();
    static {
        try {
            platform=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            platform.createContext("/api/tasks",exchange->{
                String user=exchange.getRequestHeaders().getFirst("X-Work-User-Id");
                boolean authorized="test-internal-secret".equals(exchange.getRequestHeaders().getFirst("X-Internal-Secret"));
                String owner=ownerOverride.get()==null?user:ownerOverride.get();
                String body="{\"code\":0,\"data\":[{\"id\":\""+user+"\",\"user_id\":\""+owner+"\",\"title\":\"本人的工作\",\"status\":\"pending\",\"priority\":\"high\"}]}";
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
                exchange.sendResponseHeaders(!authorized?401:failure.get()?503:200,bytes.length);
                exchange.getResponseBody().write(bytes);exchange.close();
            });
            platform.start();
        }catch(Exception ex){throw new IllegalStateException(ex);}
    }
    @DynamicPropertySource static void workUrl(DynamicPropertyRegistry registry) {
        registry.add("daily.work-url",()->"http://127.0.0.1:"+platform.getAddress().getPort());
    }
    @AfterAll static void stop(){platform.stop(0);}
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired DailySources sources;
    @Autowired DailyService daily;
    @MockBean Clock clock;

    @BeforeEach void setup() {
        failure.set(false);ownerOverride.set(null);db.execute("DROP TABLE IF EXISTS daily_records");db.execute("DROP TABLE IF EXISTS daily_settings");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V17__Personal_Daily_Planning.sql")).execute(db.getDataSource());
        Instant now=Instant.parse("2026-10-10T00:30:00Z");
        when(clock.instant()).thenReturn(now);when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.withZone(any())).thenAnswer(call->Clock.fixed(now,call.getArgument(0)));
    }

    JsonNode request(String path,String token,Long family,String body) throws Exception {
        var request=post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if(token!=null)request.header("X-Access-Token",token);
        if(family!=null)request.header("X-Family-Id",family);
        return json.readTree(mvc.perform(request).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("data");
    }
    JsonNode account() throws Exception {
        return request("/api/auth/register",null,null,"{\"email\":\"sources-"+UUID.randomUUID()+"@example.test\",\"nickname\":\"聚合验收\",\"password\":\"password123\",\"createFamily\":false}");
    }

    @Test void only_assigned_tasks_and_accessible_calendar_survive_membership_loss() throws Exception {
        JsonNode first=account(),second=account();long user=first.path("userId").asLong();
        String token=first.path("accessToken").asText(),otherToken=second.path("accessToken").asText();
        long family=request("/api/families",token,null,"{\"name\":\"本人家庭\"}").path("id").asLong();
        long other=request("/api/families",otherToken,null,"{\"name\":\"他人家庭\"}").path("id").asLong();
        // H2 的 JSON 字符串绑定与 PostgreSQL 不同，仅在夹具中修正空对象和数组。
        db.update("UPDATE person_profiles SET profile_data=JSON '{}',tags=JSON '[]' WHERE family_id IN (?,?)",family,other);
        request("/api/tasks",token,family,"{\"title\":\"本人家务\",\"assigneeId\":"+user+"}");
        request("/api/tasks",token,family,"{\"title\":\"未指派家务\"}");
        request("/api/tasks",otherToken,other,"{\"title\":\"他人的私人家务\",\"assigneeId\":"+second.path("userId").asLong()+"}");
        request("/api/calendar/events",token,family,"{\"title\":\"家庭日程\",\"eventDate\":\"2026-10-10\",\"eventTime\":\"09:00\"}");
        String collected=sources.collect(user,LocalDate.of(2026,10,10)).toString();
        assertTrue(collected.contains("本人家务"),"应聚合本人任务");
        assertTrue(collected.contains("家庭日程"),"应聚合可访问日程");
        assertTrue(collected.contains("本人的工作"),"应正确解码工作标题");
        assertFalse(collected.contains("他人的私人家务"));assertFalse(collected.contains("未指派家务"));
        daily.generate(user,"PLAN");db.update("UPDATE family_members SET left_at=CURRENT_TIMESTAMP WHERE family_id=? AND user_id=?",family,user);
        String history=daily.read(user,LocalDate.of(2026,10,10),"PLAN").toString();
        assertFalse(history.contains("本人家务"));assertFalse(history.contains("家庭日程"));assertTrue(history.contains("unavailable"));
    }

    @Test void work_outage_is_not_reported_as_no_work() throws Exception {
        long user=account().path("userId").asLong();failure.set(true);
        assertEquals(ResultCode.DAILY_SOURCE_UNAVAILABLE,assertThrows(ApiException.class,
                ()->daily.generate(user,"PLAN")).getCode());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM daily_records WHERE user_id=?",Integer.class,user));
    }

    @Test void shared_legacy_or_wrong_user_work_is_rejected_without_snapshot() throws Exception {
        long user=account().path("userId").asLong();
        for(String owner:List.of("default",Long.toString(user+1))) {
            ownerOverride.set(owner);
            assertEquals(ResultCode.DAILY_SOURCE_UNAVAILABLE,assertThrows(ApiException.class,
                ()->daily.generate(user,"PLAN")).getCode());
            assertEquals(0,db.queryForObject("SELECT count(*) FROM daily_records WHERE user_id=?",Integer.class,user));
        }
    }
}
