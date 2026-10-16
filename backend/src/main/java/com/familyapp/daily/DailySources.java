package com.familyapp.daily;

import com.familyapp.calendar.*;
import com.familyapp.family.*;
import com.familyapp.task.*;
import com.familyapp.common.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import java.time.*;
import java.util.*;

/** 每次读取重新聚合授权数据，不把历史快照视为永久授权。 */
@Service
public class DailySources {
    private final FamilyMemberRepository members;
    private final FamilyRepository families;
    private final TaskRepository tasks;
    private final CalendarEventRepository events;
    private final CalendarRecurrenceMatcher matcher;
    private final ObjectMapper json;
    private final RestTemplate http;
    private final String workUrl;
    private final String secret;

    public DailySources(FamilyMemberRepository members, FamilyRepository families,
            TaskRepository tasks, CalendarEventRepository events, CalendarRecurrenceMatcher matcher,
            ObjectMapper json, @Value("${daily.work-url:http://tp-task-service:8100}") String workUrl,
            @Value("${internal-api.secret}") String secret) {
        this.members=members; this.families=families; this.tasks=tasks; this.events=events;
        this.matcher=matcher; this.json=json; this.workUrl=workUrl; this.secret=secret;
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000); factory.setReadTimeout(5000);
        this.http=new RestTemplate(factory);
    }

    public ArrayNode collect(Long userId, LocalDate date) {
        ArrayNode result=json.createArrayNode();
        for (var member:members.findAllByUserIdAndLeftAtIsNull(userId)) {
            Long familyId=member.getFamilyId();
            if (families.findByIdAndDeletedAtIsNull(familyId).isEmpty()) continue;
            for (Task task:tasks.findAll(TaskAssignees.forUser(familyId,userId))) {
                ObjectNode item=item("family",task.getId().toString(),task.getTitle(),task.getStatus().name(),
                        task.getPriority().name(),task.getDueDate()==null ? null : task.getDueDate().toString());
                item.put("familyId",familyId);
                item.put("createdAt",task.getCreatedAt().toString());
                if(task.getDueTime()!=null) item.put("time",task.getDueTime());
                result.add(item);
            }
            for (CalendarEvent event:events.findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(familyId)) {
                if (!matcher.matchesToday(event,date)) continue;
                ObjectNode item=item("calendar",event.getId().toString(),event.getTitle(),"EVENT","HIGH",date.toString());
                item.put("familyId",familyId); item.put("time",event.getEventTime()); result.add(item);
                item.put("createdAt",event.getCreatedAt().toString());
            }
        }
        try {
            HttpHeaders headers=new HttpHeaders();
            headers.set("X-Internal-Secret",secret); headers.set("X-Work-User-Id",userId.toString());
            var response=http.exchange(workUrl+"/api/tasks",HttpMethod.GET,new HttpEntity<>(headers),String.class);
            JsonNode body=json.readTree(response.getBody());
            if(body.path("code").asInt(-1)!=0 || !body.path("data").isArray()) throw new IllegalStateException();
            for(JsonNode task:body.path("data")) {
                // 防止部分升级时旧共享服务或错误作用域的数据进入个人快照。
                if(!task.path("user_id").isTextual() || !userId.toString().equals(task.path("user_id").asText()))
                    throw new IllegalStateException();
                String deadline=null;
                if(!task.path("due_date").isNull() && !task.path("due_date").isMissingNode()) {
                    deadline=OffsetDateTime.parse(task.path("due_date").asText())
                            .atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
                }
                ObjectNode item=item("work",task.path("id").asText(),task.path("title").asText(),
                        task.path("status").asText().toUpperCase(),task.path("priority").asText().toUpperCase(),deadline);
                if(task.path("created_at").isTextual())item.put("createdAt",task.path("created_at").asText());
                result.add(item);
            }
        } catch(Exception ex) {
            throw new ApiException(ResultCode.DAILY_SOURCE_UNAVAILABLE);
        }
        return result;
    }

    private ObjectNode item(String source,String id,String title,String status,String priority,String due) {
        ObjectNode item=json.createObjectNode(); item.put("key",source+":"+id); item.put("source",source);
        item.put("id",id); item.put("title",title); item.put("status",status); item.put("priority",priority);
        if(due!=null)item.put("dueDate",due);
        return item;
    }
}
