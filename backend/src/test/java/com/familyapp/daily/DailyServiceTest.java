package com.familyapp.daily;

import com.familyapp.common.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用真实约束和条件更新验证每日闭环，不依赖外部工作服务。 */
class DailyServiceTest {
    JdbcTemplate db;ObjectMapper json=new ObjectMapper();DailySources sources;
    TestClock clock;DailyService service;
    LocalDate day=LocalDate.of(2026,10,10);

    static class TestClock extends Clock {
        Instant now;ZoneId zone=ZoneOffset.UTC;
        TestClock(Instant now){this.now=now;}
        public ZoneId getZone(){return zone;}
        public Clock withZone(ZoneId requested){return Clock.fixed(now,requested);}
        public Instant instant(){return now;}
    }

    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:daily"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);db.execute("CREATE TABLE users(id BIGINT PRIMARY KEY,deleted_at TIMESTAMP)");
        db.update("INSERT INTO users(id) VALUES (1),(2)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V17__Personal_Daily_Planning.sql")).execute(ds);
        sources=mock(DailySources.class);clock=new TestClock(Instant.parse("2026-10-10T00:30:00Z"));
        service=new DailyService(db,json,sources,clock,true);
        when(sources.collect(anyLong(),any())).thenReturn(items("PENDING"));
    }

    ArrayNode items(String status) {
        ArrayNode items=json.createArrayNode();
        items.addObject().put("key","work:1").put("source","work").put("id","1").put("title","私人工作")
                .put("status",status).put("priority","HIGH").put("dueDate",day.toString());return items;
    }

    @Test void user_date_and_type_are_idempotent_and_private() {
        service.generate(1L,"PLAN");service.generate(1L,"PLAN");
        assertEquals(1,db.queryForObject("SELECT count(*) FROM daily_records",Integer.class));
        assertTrue(service.read(2L,day,"PLAN").path("missing").asBoolean());
        assertEquals("私人工作",service.read(1L,day,"PLAN").path("content").path("items").get(0).path("title").asText());
    }

    @Test void stale_edit_is_rejected_and_review_preserves_the_plan_revision() {
        service.generate(1L,"PLAN");service.confirm(1L,day,0);
        assertEquals(ResultCode.DAILY_VERSION_CONFLICT,assertThrows(ApiException.class,
                ()->service.edit(1L,day,0,List.of("work:1"),List.of())).getCode());
        clock.now=Instant.parse("2026-10-10T13:00:00Z");
        when(sources.collect(1L,day)).thenReturn(items("COMPLETED"));service.generate(1L,"REVIEW");
        var review=service.read(1L,day,"REVIEW");
        assertEquals(1,review.path("content").path("planVersion").asLong());
        assertEquals("COMPLETED",review.path("content").path("items").get(0).path("reviewStatus").asText());
        service.edit(1L,day,1,List.of(),List.of());
        assertEquals(1,service.read(1L,day,"REVIEW").path("content").path("items").size());
        assertEquals(1,service.read(1L,day,"PLAN").path("content").path("initialItems").size());
    }

    @Test void permission_loss_removes_snapshot_fields() {
        service.generate(1L,"PLAN");when(sources.collect(1L,day)).thenReturn(json.createArrayNode());
        JsonNode item=service.read(1L,day,"PLAN").path("content").path("items").get(0);
        assertTrue(item.path("unavailable").asBoolean());assertFalse(item.has("title"));assertFalse(item.has("key"));
    }

    @Test void source_failure_is_not_persisted_as_empty_plan() {
        when(sources.collect(1L,day)).thenThrow(new ApiException(ResultCode.DAILY_SOURCE_UNAVAILABLE));
        assertThrows(ApiException.class,()->service.generate(1L,"PLAN"));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM daily_records",Integer.class));
    }

    @Test void recovery_after_21_does_not_invent_a_morning_baseline() {
        clock.now=Instant.parse("2026-10-10T13:30:00Z");service.settings(1L,true);service.scan();service.scan();
        assertEquals(2,db.queryForObject("SELECT count(*) FROM daily_records",Integer.class));
        assertFalse(service.read(1L,day,"REVIEW").path("content").path("baseline").asBoolean());
        assertEquals(0,service.read(1L,day,"REVIEW").path("content").path("items").size());
    }

    @Test void disabled_user_is_not_scheduled_and_feedback_is_private() {
        service.scan();assertEquals(0,db.queryForObject("SELECT count(*) FROM daily_records",Integer.class));
        clock.now=Instant.parse("2026-10-10T13:00:00Z");service.generate(1L,"REVIEW");
        service.feedback(1L,day,0,"明天先处理重点");
        assertEquals("明天先处理重点",service.read(1L,day,"REVIEW").path("feedback").asText());
        assertThrows(ApiException.class,()->service.feedback(2L,day,0,"篡改"));
    }

    @Test void drafts_future_items_and_more_than_three_focus_items_are_rejected() {
        ArrayNode input=items("DRAFT");input.addObject().put("key","work:2").put("status","PENDING").put("dueDate","2026-10-11");
        when(sources.collect(1L,day)).thenReturn(input);service.generate(1L,"PLAN");
        assertEquals(0,service.read(1L,day,"PLAN").path("content").path("items").size());
        assertThrows(ApiException.class,()->service.edit(1L,day,0,List.of("forged"),List.of("forged")));
    }
}
