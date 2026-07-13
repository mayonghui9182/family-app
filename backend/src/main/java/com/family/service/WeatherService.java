package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.entity.LifeIndex;
import com.family.entity.WeatherCity;
import com.family.entity.WeatherForecast;
import com.family.entity.WeatherRecord;
import com.family.mapper.LifeIndexMapper;
import com.family.mapper.WeatherCityMapper;
import com.family.mapper.WeatherForecastMapper;
import com.family.mapper.WeatherRecordMapper;
import com.family.vo.LifeIndexVO;
import com.family.vo.WeatherCurrentVO;
import com.family.vo.WeatherForecastVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 天气服务
 */
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final WeatherCityMapper weatherCityMapper;
    private final WeatherRecordMapper weatherRecordMapper;
    private final WeatherForecastMapper weatherForecastMapper;
    private final LifeIndexMapper lifeIndexMapper;

    /**
     * 获取当前天气
     *
     * @param city 城市名称
     * @return 当前天气
     */
    public WeatherCurrentVO getCurrentWeather(String city) {
        WeatherCity weatherCity = weatherCityMapper.selectOne(
                new LambdaQueryWrapper<WeatherCity>()
                        .eq(WeatherCity::getCityName, city)
                        .last("LIMIT 1")
        );

        if (weatherCity == null) {
            return null;
        }

        WeatherRecord record = weatherRecordMapper.selectOne(
                new LambdaQueryWrapper<WeatherRecord>()
                        .eq(WeatherRecord::getCityId, weatherCity.getId())
                        .orderByDesc(WeatherRecord::getRecordDate)
                        .last("LIMIT 1")
        );

        if (record == null) {
            return null;
        }

        WeatherCurrentVO vo = new WeatherCurrentVO();
        vo.setCityName(weatherCity.getCityName());
        vo.setRecordDate(record.getRecordDate());
        vo.setWeatherType(record.getWeatherType());
        vo.setWeatherIcon(record.getWeatherIcon());
        vo.setTemperature(record.getTemperature());
        vo.setTempMin(record.getTempMin());
        vo.setTempMax(record.getTempMax());
        vo.setFeelsLike(record.getFeelsLike());
        vo.setHumidity(record.getHumidity());
        vo.setWindPower(record.getWindPower());
        vo.setWindDirection(record.getWindDirection());
        vo.setAirQuality(record.getAirQuality());
        vo.setAqi(record.getAqi());
        vo.setUvIndex(record.getUvIndex());
        vo.setVisibility(record.getVisibility());
        vo.setPressure(record.getPressure());

        return vo;
    }

    /**
     * 获取7天天气预报
     *
     * @param city 城市名称
     * @return 天气预报列表
     */
    public List<WeatherForecastVO> getForecast(String city) {
        WeatherCity weatherCity = weatherCityMapper.selectOne(
                new LambdaQueryWrapper<WeatherCity>()
                        .eq(WeatherCity::getCityName, city)
                        .last("LIMIT 1")
        );

        if (weatherCity == null) {
            return List.of();
        }

        List<WeatherForecast> forecasts = weatherForecastMapper.selectList(
                new LambdaQueryWrapper<WeatherForecast>()
                        .eq(WeatherForecast::getCityId, weatherCity.getId())
                        .ge(WeatherForecast::getForecastDate, LocalDate.now())
                        .orderByAsc(WeatherForecast::getForecastDate)
                        .last("LIMIT 7")
        );

        return forecasts.stream().map(f -> {
            WeatherForecastVO vo = new WeatherForecastVO();
            vo.setForecastDate(f.getForecastDate());
            vo.setWeekday(f.getWeekday());
            vo.setDayWeather(f.getDayWeather());
            vo.setNightWeather(f.getNightWeather());
            vo.setDayIcon(f.getDayIcon());
            vo.setNightIcon(f.getNightIcon());
            vo.setTempHigh(f.getTempHigh());
            vo.setTempLow(f.getTempLow());
            vo.setDayWindPower(f.getDayWindPower());
            vo.setDayWindDirection(f.getDayWindDirection());
            vo.setNightWindPower(f.getNightWindPower());
            vo.setNightWindDirection(f.getNightWindDirection());
            vo.setPrecipitationProbability(f.getPrecipitationProbability());
            vo.setSunrise(f.getSunrise());
            vo.setSunset(f.getSunset());
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 获取生活指数
     *
     * @param city 城市名称
     * @return 生活指数列表
     */
    public List<LifeIndexVO> getLifeIndex(String city) {
        WeatherCity weatherCity = weatherCityMapper.selectOne(
                new LambdaQueryWrapper<WeatherCity>()
                        .eq(WeatherCity::getCityName, city)
                        .last("LIMIT 1")
        );

        if (weatherCity == null) {
            return List.of();
        }

        List<LifeIndex> indices = lifeIndexMapper.selectList(
                new LambdaQueryWrapper<LifeIndex>()
                        .eq(LifeIndex::getCityId, weatherCity.getId())
                        .orderByDesc(LifeIndex::getIndexDate)
                        .last("LIMIT 10")
        );

        return indices.stream().map(i -> {
            LifeIndexVO vo = new LifeIndexVO();
            vo.setIndexType(i.getIndexType());
            vo.setIndexName(i.getIndexName());
            vo.setLevel(i.getLevel());
            vo.setLevelDesc(i.getLevelDesc());
            vo.setSuggestion(i.getSuggestion());
            return vo;
        }).collect(Collectors.toList());
    }
}
