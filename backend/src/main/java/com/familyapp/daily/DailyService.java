package com.familyapp.daily;

import com.familyapp.common.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** 每日记录使用数据库防重和版本条件更新，不修改原任务。 */
@Service
public class DailyService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final DailySources sources;
    private final Clock clock;
    private final boolean enabled;

    public DailyService(JdbcTemplate db,ObjectMapper json,DailySources sources,Clock clock,
            @Value("${daily.enabled:false}") boolean enabled) {
        this.db=db;this.json=json;this.sources=sources;this.clock=clock;this.enabled=enabled;
    }

    public LocalDate today(){return LocalDate.now(clock.withZone(ZONE));}
    public boolean reviewDue(){return !LocalTime.now(clock.withZone(ZONE)).isBefore(LocalTime.of(21,0));}
    public void guard(){if(!enabled)throw new ApiException(ResultCode.DAILY_DISABLED);}

    public Map<String,Object> settings(Long user) {
        var rows=db.queryForList("SELECT enabled FROM daily_settings WHERE user_id=?",Boolean.class,user);
        return Map.of("available",enabled,"enabled",!rows.isEmpty() && rows.get(0),"timezone","Asia/Shanghai");
    }

    public Map<String,Object> settings(Long user,boolean value) {
        guard();
        if(db.update("UPDATE daily_settings SET enabled=? WHERE user_id=?",value,user)==0) {
            try {db.update("INSERT INTO daily_settings(user_id,enabled) VALUES (?,?)",user,value);}
            catch(DataIntegrityViolationException ex){db.update("UPDATE daily_settings SET enabled=? WHERE user_id=?",value,user);}
        }
        return settings(user);
    }

    private ObjectNode raw(Long user,LocalDate date,String kind) {
        var rows=db.query("SELECT content,version,confirmed,feedback,generated_at FROM daily_records "
                +"WHERE user_id=? AND plan_date=? AND kind=?",(rs,n)->{
            ObjectNode row=json.createObjectNode();
            try{row.set("content",json.readTree(rs.getString("content")));}
            catch(Exception ex){throw new IllegalStateException("每日记录格式异常");}
            row.put("version",rs.getLong("version"));row.put("confirmed",rs.getBoolean("confirmed"));
            row.put("feedback",rs.getString("feedback"));row.put("generatedAt",rs.getTimestamp("generated_at").toInstant().toString());
            row.put("date",date.toString());row.put("kind",kind);return row;
        },user,date,kind);
        return rows.isEmpty()?null:rows.get(0);
    }

    public ObjectNode read(Long user,LocalDate date,String kind) {
        guard();ObjectNode row=raw(user,date,kind);
        if(row==null){ObjectNode missing=json.createObjectNode();missing.put("missing",true);return missing;}
        Map<String,JsonNode> live=live(user,date);
        for(String field:List.of("items","initialItems","additionalItems")) {
            if(!row.path("content").has(field))continue;
            ArrayNode filtered=json.createArrayNode();
            for(JsonNode item:row.path("content").path(field)) {
                if(live.containsKey(item.path("key").asText()))filtered.add(item);
                else {ObjectNode hidden=json.createObjectNode();hidden.put("unavailable",true);filtered.add(hidden);}
            }
            ((ObjectNode)row.get("content")).set(field,filtered);
        }
        return row;
    }

    private Map<String,JsonNode> live(Long user,LocalDate date) {
        Map<String,JsonNode> result=new HashMap<>();
        for(JsonNode item:sources.collect(user,date))result.put(item.path("key").asText(),item);
        return result;
    }

    public void generate(Long user,String kind) {
        guard();
        if(!Set.of("PLAN","REVIEW").contains(kind))throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION);
        if(kind.equals("REVIEW") && !reviewDue())throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,"21:00后可生成当日复盘");
        LocalDate date=today();if(raw(user,date,kind)!=null)return;
        var now=clock.instant();ObjectNode content=json.createObjectNode();ArrayNode items=json.createArrayNode();
        if(kind.equals("PLAN")) {
            List<JsonNode> candidates=new ArrayList<>();
            for(JsonNode item:sources.collect(user,date)) {
                String status=item.path("status").asText();String due=item.path("dueDate").asText();
                if(!Set.of("PENDING","IN_PROGRESS","EVENT").contains(status))continue;
                if(!due.isEmpty() && LocalDate.parse(due).isAfter(date))continue;
                candidates.add(item);
            }
            candidates.sort(Comparator.comparing((JsonNode n)->n.path("dueDate").asText("9999-12-31"))
                    .thenComparing(n->switch(n.path("priority").asText()){case "HIGH"->0;case "MEDIUM"->1;default->2;})
                    .thenComparing(n->n.path("key").asText()));
            int count=0;for(JsonNode item:candidates){ObjectNode copy=item.deepCopy();copy.put("focus",count++<3);items.add(copy);}
            content.set("initialItems",items.deepCopy());
            content.put("baseline",LocalTime.now(clock.withZone(ZONE)).isBefore(LocalTime.of(21,0)));
            content.put("late",!LocalTime.now(clock.withZone(ZONE)).isBefore(LocalTime.of(8,31)));
        } else {
            ObjectNode plan=raw(user,date,"PLAN");Map<String,JsonNode> current=live(user,date);
            boolean baseline=plan!=null && plan.path("content").path("baseline").asBoolean();
            content.put("baseline",baseline);content.put("planVersion",plan==null?-1:plan.path("version").asLong());
            content.put("cutoff",clock.instant().toString());
            Set<String> planned=new HashSet<>();
            if(baseline)for(JsonNode original:plan.path("content").path("items")) {
                planned.add(original.path("key").asText());
                JsonNode actual=current.get(original.path("key").asText());
                if(actual==null){ObjectNode hidden=json.createObjectNode();hidden.put("unavailable",true);items.add(hidden);}
                else {ObjectNode copy=original.deepCopy();copy.put("reviewStatus",actual.path("status").asText());items.add(copy);}
            }
            ArrayNode additional=json.createArrayNode();
            for(JsonNode actual:current.values()) {
                if(planned.contains(actual.path("key").asText()) || actual.path("status").asText().equals("DRAFT"))continue;
                String created=actual.path("createdAt").asText();
                if(created.isEmpty())continue;
                boolean createdToday=OffsetDateTime.parse(created).atZoneSameInstant(ZONE).toLocalDate().equals(date);
                if(createdToday)additional.add(actual);
            }
            content.set("additionalItems",additional);
        }
        content.set("items",items);
        try {db.update("INSERT INTO daily_records(user_id,plan_date,kind,content,generated_at) VALUES (?,?,?,?,?)",
                user,date,kind,content.toString(),Timestamp.from(now));}
        catch(DataIntegrityViolationException ex){if(raw(user,date,kind)==null)throw ex;}
    }

    public ObjectNode edit(Long user,LocalDate date,long version,List<String> order,List<String> focus) {
        guard();if(!date.equals(today()))throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,"只能调整今日计划");
        ObjectNode row=raw(user,date,"PLAN");if(row==null)throw new ApiException(ResultCode.TASK_NOT_FOUND,"计划尚未生成");
        Map<String,JsonNode> current=live(user,date);Map<String,JsonNode> existing=new LinkedHashMap<>();
        for(JsonNode item:row.path("content").path("items"))
            if(current.containsKey(item.path("key").asText()))existing.put(item.path("key").asText(),item);
        if(focus==null || order==null || focus.size()>3 || new HashSet<>(order).size()!=order.size()
                || new HashSet<>(focus).size()!=focus.size() || !existing.keySet().containsAll(order)
                || !order.containsAll(focus))throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,"计划项目或重点无效");
        ArrayNode items=json.createArrayNode();for(String key:order){ObjectNode item=existing.get(key).deepCopy();item.put("focus",focus.contains(key));items.add(item);}
        ObjectNode content=row.path("content").deepCopy();content.set("items",items);
        changed(db.update("UPDATE daily_records SET content=?,confirmed=false,version=version+1 "
                +"WHERE user_id=? AND plan_date=? AND kind='PLAN' AND version=?",content.toString(),user,date,version));
        return read(user,date,"PLAN");
    }

    public ObjectNode confirm(Long user,LocalDate date,long version) {
        guard();if(!date.equals(today()))throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,"只能确认今日计划");
        read(user,date,"PLAN");
        changed(db.update("UPDATE daily_records SET confirmed=true,version=version+1 "
                +"WHERE user_id=? AND plan_date=? AND kind='PLAN' AND version=?",user,date,version));
        return read(user,date,"PLAN");
    }

    public ObjectNode feedback(Long user,LocalDate date,long version,String text) {
        guard();if(text==null || text.length()>2000)throw new ApiException(ResultCode.BAD_REQUEST_VALIDATION,"复盘反馈最多2000字");
        changed(db.update("UPDATE daily_records SET feedback=?,version=version+1 "
                +"WHERE user_id=? AND plan_date=? AND kind='REVIEW' AND version=?",text,user,date,version));
        return read(user,date,"REVIEW");
    }

    private void changed(int count){if(count!=1)throw new ApiException(ResultCode.DAILY_VERSION_CONFLICT);}

    public void scan() {
        if(!enabled)return;LocalTime time=LocalTime.now(clock.withZone(ZONE));
        if(time.isBefore(LocalTime.of(8,30)))return;
        var users=db.queryForList("SELECT s.user_id FROM daily_settings s JOIN users u ON u.id=s.user_id "
                +"WHERE s.enabled=true AND u.deleted_at IS NULL",Long.class);
        for(Long user:users) {
            try {generate(user,"PLAN");if(!time.isBefore(LocalTime.of(21,0)))generate(user,"REVIEW");}
            catch(Exception ex){org.slf4j.LoggerFactory.getLogger(DailyService.class).warn("每日记录生成失败，稍后重试");}
        }
    }
}
