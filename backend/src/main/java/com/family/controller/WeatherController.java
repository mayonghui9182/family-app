package com.family.controller;

import com.family.common.result.Result;
import com.family.service.WeatherService;
import com.family.vo.LifeIndexVO;
import com.family.vo.WeatherCurrentVO;
import com.family.vo.WeatherForecastVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 天气控制器
 */
@Tag(name = "天气管理", description = "天气相关接口")
@RestController
@RequestMapping("/weather")
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    /**
     * 获取当前天气
     *
     * @param city 城市名称
     * @return 当前天气
     */
    @Operation(summary = "获取当前天气", description = "根据城市名称获取当前天气信息")
    @GetMapping("/current")
    public Result<WeatherCurrentVO> getCurrentWeather(
            @Parameter(description = "城市名称", example = "北京")
            @RequestParam(defaultValue = "北京") String city) {
        WeatherCurrentVO weather = weatherService.getCurrentWeather(city);
        return Result.success(weather);
    }

    /**
     * 获取7天天气预报
     *
     * @param city 城市名称
     * @return 天气预报列表
     */
    @Operation(summary = "获取7天预报", description = "根据城市名称获取7天天气预报")
    @GetMapping("/forecast")
    public Result<List<WeatherForecastVO>> getForecast(
            @Parameter(description = "城市名称", example = "北京")
            @RequestParam(defaultValue = "北京") String city) {
        List<WeatherForecastVO> forecast = weatherService.getForecast(city);
        return Result.success(forecast);
    }

    /**
     * 获取生活指数
     *
     * @param city 城市名称
     * @return 生活指数列表
     */
    @Operation(summary = "获取生活指数", description = "根据城市名称获取生活指数信息")
    @GetMapping("/life-index")
    public Result<List<LifeIndexVO>> getLifeIndex(
            @Parameter(description = "城市名称", example = "北京")
            @RequestParam(defaultValue = "北京") String city) {
        List<LifeIndexVO> lifeIndex = weatherService.getLifeIndex(city);
        return Result.success(lifeIndex);
    }
}
