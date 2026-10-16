package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * C-15 routine 到期匹配器：解析 rule_templates.conditions JSONB（4 种结构），
 * 返回今日命中的日常模板列表。
 *
 * <p>条件结构（规格 §3.4）：</p>
 * <ul>
 *   <li>weekly: {"kind":"weekly","weekdays":[1..5],"time":"HH:mm"} —— ISO 周一=1</li>
 *   <li>monthly: {"kind":"monthly","day":1,"time":"HH:mm"}</li>
 *   <li>weather: {"kind":"weather","weather_type":"sunny","city":"北京"} —— 经 WeatherClient 查询，
 *       对端降级返回 unknown 视为不命中；调用异常则整模板本轮跳过（56501 语义，log.warn 中文）</li>
 *   <li>combo: {"kind":"combo","op":"AND|OR","items":[上述任意结构递归]}</li>
 * </ul>
 */
@Service
public class RoutineMatcherService {

    private static final Logger log = LoggerFactory.getLogger(RoutineMatcherService.class);

    private final RuleTemplateRepository templateRepository;
    private final WeatherClient weatherClient;
    private final Clock clock;

    public RoutineMatcherService(RuleTemplateRepository templateRepository,
                                 WeatherClient weatherClient,
                                 Clock clock) {
        this.templateRepository = templateRepository;
        this.weatherClient = weatherClient;
        this.clock = clock;
    }

    /** 今日命中列表；单模板解析失败仅告警跳过，不阻断同批其它模板。 */
    public List<DueTodayRoutine> dueToday(Long familyId) {
        LocalDate today = LocalDate.now(clock);
        List<DueTodayRoutine> hits = new ArrayList<>();
        for (RuleTemplate template : templateRepository.findByFamilyIdAndTypeAndIsActiveTrue(familyId, "routine")) {
            try {
                if (evaluate(template.getConditions(), today)) {
                    hits.add(toDto(template));
                }
            } catch (Exception e) {
                log.warn("routine 模板[{}]本轮跳过：{}", template.getName(), e.getMessage());
            }
        }
        return hits;
    }

    /** 单模板条件求值；天气调用异常向上传播，由 dueToday 统一按跳过处理。 */
    boolean evaluate(JsonNode conditions, LocalDate today) {
        String kind = conditions.path("kind").asText("");
        return switch (kind) {
            case "weekly" -> weeklyHit(conditions, today);
            case "monthly" -> monthlyHit(conditions, today);
            case "weather" -> weatherHit(conditions, today);
            case "combo" -> comboHit(conditions, today);
            default -> {
                log.warn("未知条件类型[kind={}]，视为不命中", kind);
                yield false;
            }
        };
    }

    private boolean weeklyHit(JsonNode cond, LocalDate today) {
        int isoDay = today.getDayOfWeek().getValue();
        for (JsonNode day : cond.path("weekdays")) {
            if (day.asInt() == isoDay) {
                return true;
            }
        }
        return false;
    }

    private boolean monthlyHit(JsonNode cond, LocalDate today) {
        return cond.path("day").asInt(-1) == today.getDayOfMonth();
    }

    private boolean weatherHit(JsonNode cond, LocalDate today) {
        String city = cond.path("city").asText("");
        String expected = cond.path("weather_type").asText("");
        String actual = weatherClient.fetchWeatherType(city, today);
        return !"unknown".equalsIgnoreCase(actual) && actual.equalsIgnoreCase(expected);
    }

    private boolean comboHit(JsonNode cond, LocalDate today) {
        boolean isAnd = "AND".equalsIgnoreCase(cond.path("op").asText("AND"));
        for (JsonNode item : cond.path("items")) {
            boolean hit = evaluate(item, today);
            if (isAnd && !hit) {
                return false;
            }
            if (!isAnd && hit) {
                return true;
            }
        }
        return isAnd;
    }

    private DueTodayRoutine toDto(RuleTemplate template) {
        JsonNode content = template.getContent();
        String title = content.path("title").asText(template.getName());
        String description = content.path("description").asText("");
        String priority = content.path("priority").asText("MEDIUM");
        // needs_ai 双键兼容：优先 needs_ai_decomposition，回退 needs_ai（V6 种子用后者）
        boolean needsAi = content.hasNonNull("needs_ai_decomposition")
                ? content.path("needs_ai_decomposition").asBoolean(false)
                : content.path("needs_ai").asBoolean(false);
        return new DueTodayRoutine(template.getId(), title, description, priority, needsAi);
    }
}
