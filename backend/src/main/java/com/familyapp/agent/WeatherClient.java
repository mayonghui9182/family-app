package com.familyapp.agent;

import java.time.LocalDate;

/** 天气查询接口（Change 3 的 tool-service 为对端；测试可 mock）。 */
public interface WeatherClient {

    /**
     * 查询城市当日天气类型（如 sunny/rain/cloudy）。
     *
     * @return 天气类型；第三方降级时对端返回 unknown
     * @throws RuntimeException 任一调用/解析失败（调用方按「本轮跳过该模板」处理）
     */
    String fetchWeatherType(String city, LocalDate date);
}
