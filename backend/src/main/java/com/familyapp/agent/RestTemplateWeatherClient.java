package com.familyapp.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * tool-service /api/weather 客户端实现（design.md D2）：连接/读取各 3 秒超时；
 * 任何异常向上抛出，由匹配器按「天气不可知 → 本轮跳过该模板」处理（对应 56501 语义）。
 */
@Component
public class RestTemplateWeatherClient implements WeatherClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;

    public RestTemplateWeatherClient(
            @Value("${tool-service.base-url:http://localhost:8800}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(factory);
        this.baseUrl = baseUrl;
    }

    @Override
    public String fetchWeatherType(String city, LocalDate date) {
        String url = baseUrl + "/api/weather?city=" + URLEncoder.encode(city, StandardCharsets.UTF_8)
                + "&date=" + date;
        String body = restTemplate.getForObject(url, String.class);
        try {
            JsonNode node = objectMapper.readTree(body == null ? "" : body);
            return node.path("weather_type").asText("unknown");
        } catch (Exception e) {
            throw new IllegalStateException("天气响应解析失败：" + e.getMessage(), e);
        }
    }
}
