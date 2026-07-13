package com.family.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.entity.Baby;
import com.family.entity.BabyVaccine;
import com.family.entity.GrowthRecord;
import com.family.entity.HouseholdItem;
import com.family.entity.ItemRecord;
import com.family.entity.LifeIndex;
import com.family.entity.Milestone;
import com.family.entity.Reminder;
import com.family.entity.Todo;
import com.family.entity.TravelGuide;
import com.family.entity.TravelHighlight;
import com.family.entity.Vaccine;
import com.family.entity.WeatherCity;
import com.family.entity.WeatherForecast;
import com.family.entity.WeatherRecord;
import com.family.mapper.BabyMapper;
import com.family.mapper.BabyVaccineMapper;
import com.family.mapper.GrowthRecordMapper;
import com.family.mapper.HouseholdItemMapper;
import com.family.mapper.ItemRecordMapper;
import com.family.mapper.LifeIndexMapper;
import com.family.mapper.MilestoneMapper;
import com.family.mapper.ReminderMapper;
import com.family.mapper.TodoMapper;
import com.family.mapper.TravelGuideMapper;
import com.family.mapper.TravelHighlightMapper;
import com.family.mapper.VaccineMapper;
import com.family.mapper.WeatherCityMapper;
import com.family.mapper.WeatherForecastMapper;
import com.family.mapper.WeatherRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 数据初始化器
 * 应用启动时初始化Mock数据
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final WeatherCityMapper weatherCityMapper;
    private final WeatherRecordMapper weatherRecordMapper;
    private final WeatherForecastMapper weatherForecastMapper;
    private final LifeIndexMapper lifeIndexMapper;
    private final TravelGuideMapper travelGuideMapper;
    private final TravelHighlightMapper travelHighlightMapper;
    private final ReminderMapper reminderMapper;
    private final TodoMapper todoMapper;
    private final BabyMapper babyMapper;
    private final GrowthRecordMapper growthRecordMapper;
    private final VaccineMapper vaccineMapper;
    private final BabyVaccineMapper babyVaccineMapper;
    private final MilestoneMapper milestoneMapper;
    private final HouseholdItemMapper householdItemMapper;
    private final ItemRecordMapper itemRecordMapper;

    private static final Long USER_ID = 1L;
    private static final Long FAMILY_ID = 1L;

    @Override
    public void run(ApplicationArguments args) {
        log.info("开始初始化Mock数据...");

        initWeatherData();
        initTravelData();
        initReminderData();
        initTodoData();
        initBabyData();
        initHouseholdItemData();

        log.info("Mock数据初始化完成！");
    }

    /**
     * 初始化天气数据
     */
    private void initWeatherData() {
        WeatherCity existingCity = weatherCityMapper.selectOne(
                new LambdaQueryWrapper<WeatherCity>().eq(WeatherCity::getCityName, "北京")
        );
        if (existingCity != null) {
            log.info("天气数据已存在，跳过初始化");
            return;
        }

        WeatherCity city = new WeatherCity();
        city.setCityName("北京");
        city.setCityCode("101010100");
        city.setProvince("北京市");
        city.setCountry("中国");
        city.setLongitude("116.405285");
        city.setLatitude("39.904989");
        city.setSortOrder(1);
        city.setCreateTime(LocalDateTime.now());
        city.setUpdateTime(LocalDateTime.now());
        weatherCityMapper.insert(city);
        Long cityId = city.getId();

        WeatherRecord record = new WeatherRecord();
        record.setCityId(cityId);
        record.setRecordDate(LocalDate.now());
        record.setWeatherType("晴");
        record.setWeatherIcon("sunny");
        record.setTemperature(new BigDecimal("28.5"));
        record.setTempMin(new BigDecimal("22.0"));
        record.setTempMax(new BigDecimal("31.0"));
        record.setFeelsLike(new BigDecimal("30.2"));
        record.setHumidity(45);
        record.setWindPower("3级");
        record.setWindDirection("东南风");
        record.setAirQuality("良");
        record.setAqi(68);
        record.setUvIndex("中等");
        record.setVisibility(new BigDecimal("15.0"));
        record.setPressure(1013);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        weatherRecordMapper.insert(record);

        String[] weekdays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
        String[] dayWeathers = {"晴", "多云", "阴", "小雨", "晴", "多云", "晴"};
        String[] nightWeathers = {"晴", "多云", "小雨", "阴", "晴", "晴", "多云"};
        int[] highs = {31, 29, 27, 25, 28, 30, 32};
        int[] lows = {22, 21, 20, 19, 20, 21, 23};

        for (int i = 0; i < 7; i++) {
            WeatherForecast forecast = new WeatherForecast();
            forecast.setCityId(cityId);
            forecast.setForecastDate(LocalDate.now().plusDays(i));
            forecast.setWeekday(weekdays[(LocalDate.now().plusDays(i).getDayOfWeek().getValue()) % 7]);
            forecast.setDayWeather(dayWeathers[i]);
            forecast.setNightWeather(nightWeathers[i]);
            forecast.setDayIcon("day_" + dayWeathers[i]);
            forecast.setNightIcon("night_" + nightWeathers[i]);
            forecast.setTempHigh(new BigDecimal(highs[i]));
            forecast.setTempLow(new BigDecimal(lows[i]));
            forecast.setDayWindPower("3级");
            forecast.setDayWindDirection("东南风");
            forecast.setNightWindPower("2级");
            forecast.setNightWindDirection("南风");
            forecast.setPrecipitationProbability(i == 3 ? 80 : 20);
            forecast.setSunrise("05:12");
            forecast.setSunset("19:45");
            forecast.setCreateTime(LocalDateTime.now());
            forecast.setUpdateTime(LocalDateTime.now());
            weatherForecastMapper.insert(forecast);
        }

        String[][] lifeIndices = {
                {"dressing", "穿衣指数", "热", "天气炎热", "建议穿短衫、短裙、短裤、薄型T恤衫等清凉夏季服装"},
                {"uv", "紫外线指数", "中等", "紫外线强度中等", "建议涂擦SPF高于15、PA+的防晒护肤品"},
                {"sports", "运动指数", "适宜", "天气较好", "适宜户外运动，但请注意防暑降温"},
                {"car_wash", "洗车指数", "适宜", "天气较好", "适宜洗车，未来两天天气晴好"},
                {"cold", "感冒指数", "少发", "感冒发生概率低", "无需特别防护，保持良好作息"},
                {"air", "空气质量", "良", "空气质量良好", "易感人群应适当减少室外活动"}
        };

        for (String[] idx : lifeIndices) {
            LifeIndex lifeIndex = new LifeIndex();
            lifeIndex.setCityId(cityId);
            lifeIndex.setIndexDate(LocalDate.now());
            lifeIndex.setIndexType(idx[0]);
            lifeIndex.setIndexName(idx[1]);
            lifeIndex.setLevel(idx[2]);
            lifeIndex.setLevelDesc(idx[3]);
            lifeIndex.setSuggestion(idx[4]);
            lifeIndex.setCreateTime(LocalDateTime.now());
            lifeIndex.setUpdateTime(LocalDateTime.now());
            lifeIndexMapper.insert(lifeIndex);
        }

        log.info("天气数据初始化完成");
    }

    /**
     * 初始化旅游数据
     */
    private void initTravelData() {
        Long count = travelGuideMapper.selectCount(
                new LambdaQueryWrapper<TravelGuide>().eq(TravelGuide::getUserId, USER_ID)
        );
        if (count > 0) {
            log.info("旅游数据已存在，跳过初始化");
            return;
        }

        String[][] guides = {
                {"三亚5日亲子游攻略", "三亚", "2024-10-01", "2024-10-05", "5", "3", "8000", "飞机", "三亚海棠湾喜来登酒店"},
                {"云南大理丽江7日游", "云南", "2024-09-15", "2024-09-21", "7", "2", "12000", "飞机+高铁", "大理古城民宿"},
                {"杭州西湖3日游", "杭州", "2024-08-10", "2024-08-12", "3", "4", "5000", "高铁", "杭州西湖国宾馆"},
                {"成都美食之旅", "成都", "2024-07-20", "2024-07-23", "4", "2", "4000", "飞机", "成都春熙路酒店"},
                {"西安历史文化之旅", "西安", "2024-06-01", "2024-06-04", "4", "3", "6000", "高铁", "西安钟楼饭店"},
                {"厦门鼓浪屿休闲游", "厦门", "2024-05-01", "2024-05-04", "4", "2", "5500", "飞机", "厦门鼓浪屿民宿"}
        };

        for (String[] g : guides) {
            TravelGuide guide = new TravelGuide();
            guide.setUserId(USER_ID);
            guide.setTitle(g[0]);
            guide.setDestination(g[1]);
            guide.setStartDate(LocalDate.parse(g[2]));
            guide.setEndDate(LocalDate.parse(g[3]));
            guide.setDays(Integer.parseInt(g[4]));
            guide.setPeopleCount(Integer.parseInt(g[5]));
            guide.setBudget(new BigDecimal(g[6]));
            guide.setTransportation(g[7]);
            guide.setAccommodation(g[8]);
            guide.setItinerary("详细行程安排：第一天抵达后入住酒店，休息调整。第二天开始正式游览...");
            guide.setNotes("注意事项：请提前预订酒店和门票，带好防晒用品...");
            guide.setCoverImage("/images/guide_" + g[1] + ".jpg");
            guide.setStatus(1);
            guide.setCreateTime(LocalDateTime.now());
            guide.setUpdateTime(LocalDateTime.now());
            travelGuideMapper.insert(guide);

            for (int i = 1; i <= 3; i++) {
                TravelHighlight highlight = new TravelHighlight();
                highlight.setGuideId(guide.getId());
                highlight.setTitle(g[1] + "必游景点" + i);
                highlight.setDescription("这是" + g[1] + "最值得一去的景点之一，风景优美，历史悠久。");
                highlight.setImageUrl("/images/highlight_" + guide.getId() + "_" + i + ".jpg");
                highlight.setLocation(g[1] + "市区");
                highlight.setDuration(new BigDecimal("2.5"));
                highlight.setSortOrder(i);
                highlight.setCreateTime(LocalDateTime.now());
                highlight.setUpdateTime(LocalDateTime.now());
                travelHighlightMapper.insert(highlight);
            }
        }

        log.info("旅游数据初始化完成");
    }

    /**
     * 初始化提醒数据
     */
    private void initReminderData() {
        Long count = reminderMapper.selectCount(
                new LambdaQueryWrapper<Reminder>().eq(Reminder::getUserId, USER_ID)
        );
        if (count > 0) {
            log.info("提醒数据已存在，跳过初始化");
            return;
        }

        String[][] reminders = {
                {"妈妈生日", "记得给妈妈买生日礼物，订蛋糕", "birthday", "2024-08-15T09:00:00", "yearly", "1"},
                {"结婚纪念日", "准备礼物和晚餐预订", "anniversary", "2024-09-20T18:00:00", "yearly", "1"},
                {"房贷还款", "每月10号前还款", "bill", "2024-07-10T10:00:00", "monthly", "1"},
                {"信用卡还款", "每月25号还款", "bill", "2024-07-25T10:00:00", "monthly", "1"},
                {"宝宝体检", "6个月体检", "health", "2024-08-05T08:30:00", "none", "1"},
                {"闺蜜聚会", "周末下午茶", "meeting", "2024-07-13T14:00:00", "none", "1"},
                {"加油提醒", "油快用完了", "car", "2024-07-08T18:00:00", "none", "0"},
                {"交水电费", "每月月初缴费", "bill", "2024-08-01T09:00:00", "monthly", "1"}
        };

        for (String[] r : reminders) {
            Reminder reminder = new Reminder();
            reminder.setUserId(USER_ID);
            reminder.setTitle(r[0]);
            reminder.setContent(r[1]);
            reminder.setType(r[2]);
            reminder.setRemindTime(LocalDateTime.parse(r[3]).toLocalTime());
            reminder.setRepeatType(r[4]);
            reminder.setEnabled(Integer.parseInt(r[5]));
            reminder.setReminded(0);
            reminder.setRemark("");
            reminder.setCreateTime(LocalDateTime.now());
            reminder.setUpdateTime(LocalDateTime.now());
            reminderMapper.insert(reminder);
        }

        log.info("提醒数据初始化完成");
    }

    /**
     * 初始化待办数据
     */
    private void initTodoData() {
        Long count = todoMapper.selectCount(
                new LambdaQueryWrapper<Todo>().eq(Todo::getUserId, USER_ID)
        );
        if (count > 0) {
            log.info("待办数据已存在，跳过初始化");
            return;
        }

        String[][] todos = {
                {"完成季度工作总结", "工作", "high", "2024-07-10T18:00:00", "0"},
                {"买宝宝的辅食和尿不湿", "生活", "high", "2024-07-08T20:00:00", "0"},
                {"预约下周的体检", "生活", "medium", "2024-07-12T17:00:00", "0"},
                {"学习Spring Boot新特性", "学习", "low", "2024-07-20T23:59:00", "0"},
                {"整理照片和视频", "生活", "low", null, "0"},
                {"给爸妈打电话", "生活", "medium", "2024-07-07T20:00:00", "1"},
                {"读完《深入理解Java虚拟机》", "学习", "medium", "2024-07-31T23:59:00", "0"}
        };

        for (int i = 0; i < todos.length; i++) {
            String[] t = todos[i];
            Todo todo = new Todo();
            todo.setUserId(USER_ID);
            todo.setContent(t[0]);
            todo.setCategory(t[1]);
            todo.setPriority(t[2]);
            todo.setDeadline(t[3] != null ? LocalDateTime.parse(t[3]) : null);
            todo.setCompleted(Integer.parseInt(t[4]));
            todo.setCompletedTime("1".equals(t[4]) ? LocalDateTime.now().minusDays(1) : null);
            todo.setSortOrder(i);
            todo.setRemark("");
            todo.setCreateTime(LocalDateTime.now());
            todo.setUpdateTime(LocalDateTime.now());
            todoMapper.insert(todo);
        }

        log.info("待办数据初始化完成");
    }

    /**
     * 初始化宝宝数据
     */
    private void initBabyData() {
        Baby existingBaby = babyMapper.selectOne(
                new LambdaQueryWrapper<Baby>().eq(Baby::getUserId, USER_ID)
        );
        if (existingBaby != null) {
            log.info("宝宝数据已存在，跳过初始化");
            return;
        }

        Baby baby = new Baby();
        baby.setUserId(USER_ID);
        baby.setName("小橙子");
        baby.setNickname("橙子宝宝");
        baby.setGender("girl");
        baby.setBirthDate(LocalDate.of(2024, 2, 14));
        baby.setBirthTime("08:30");
        baby.setBirthWeight(3200);
        baby.setBirthHeight(new BigDecimal("50.0"));
        baby.setBloodType("A型");
        baby.setZodiac("龙");
        baby.setConstellation("水瓶座");
        baby.setAvatar("/images/baby_avatar.jpg");
        baby.setRemark("可爱的小龙女宝宝");
        baby.setCreateTime(LocalDateTime.now());
        baby.setUpdateTime(LocalDateTime.now());
        babyMapper.insert(baby);
        Long babyId = baby.getId();

        String[][] growthRecords = {
                {"2024-02-14", "50.0", "3.2", "all", "出生", "", "", "", "", "", ""},
                {"2024-03-14", "54.5", "4.2", "all", "满月体检", "", "", "", "", "", ""},
                {"2024-04-14", "58.0", "5.2", "all", "2个月体检", "", "", "", "", "", ""},
                {"2024-05-14", "61.5", "6.1", "all", "3个月体检", "", "", "", "", "", ""},
                {"2024-06-14", "64.0", "6.8", "all", "4个月体检", "", "", "", "", "", ""},
                {"2024-07-14", "66.5", "7.5", "all", "5个月体检", "", "", "", "", "", ""},
                {"2024-02-28", "52.0", "3.6", "all", "两周复查", "", "", "", "", "", ""},
                {"2024-04-01", "56.0", "4.7", "all", "一个半月", "", "", "", "", "", ""},
                {"2024-05-01", "59.5", "5.6", "all", "两个半月", "", "", "", "", "", ""},
                {"2024-06-01", "62.5", "6.4", "all", "三个半月", "", "", "", "", "", ""}
        };

        for (String[] gr : growthRecords) {
            GrowthRecord record = new GrowthRecord();
            record.setBabyId(babyId);
            record.setRecordDate(LocalDate.parse(gr[0]));
            record.setHeight(new BigDecimal(gr[1]));
            record.setWeight(new BigDecimal(gr[2]));
            record.setRecordType(gr[3]);
            record.setNote(gr[4]);
            record.setMealType(gr[5]);
            record.setFoodDesc(gr[6]);
            record.setSleepDuration(gr[7] != null && !gr[7].isEmpty() ? Integer.parseInt(gr[7]) : null);
            record.setContent(gr[8]);
            record.setRecordedBy(USER_ID);
            record.setCreateTime(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            growthRecordMapper.insert(record);
        }

        String[][] vaccines = {
                {"乙肝疫苗", "乙肝", "free", "乙型病毒性肝炎", "3", "出生时、1月龄、6月龄", "上臂三角肌"},
                {"卡介苗", "卡介苗", "free", "结核病", "1", "出生时", "上臂三角肌外下缘"},
                {"脊灰疫苗", "脊灰", "free", "脊髓灰质炎", "4", "2、3、4月龄、4周岁", "口服/注射"},
                {"百白破疫苗", "百白破", "free", "百日咳、白喉、破伤风", "4", "3、4、5月龄、18月龄", "上臂三角肌"},
                {"麻腮风疫苗", "麻腮风", "free", "麻疹、腮腺炎、风疹", "2", "8月龄、18月龄", "上臂外侧三角肌"},
                {"乙脑减毒活疫苗", "乙脑", "free", "流行性乙型脑炎", "2", "8月龄、2周岁", "上臂外侧三角肌"},
                {"A群流脑多糖疫苗", "A群流脑", "free", "A群流行性脑脊髓膜炎", "2", "6月龄、9月龄", "上臂外侧三角肌"},
                {"甲肝减毒活疫苗", "甲肝", "free", "甲型病毒性肝炎", "1", "18月龄", "上臂三角肌"},
                {"13价肺炎疫苗", "13价肺炎", "paid", "肺炎球菌疾病", "4", "2、4、6月龄、12-15月龄", "大腿前外侧/上臂"},
                {"五联疫苗", "五联", "paid", "百日咳、白喉、破伤风、脊灰、Hib", "4", "2、3、4月龄、18月龄", "大腿前外侧"},
                {"轮状病毒疫苗", "轮状病毒", "paid", "轮状病毒胃肠炎", "3", "6-32周龄", "口服"},
                {"流感疫苗", "流感", "paid", "流行性感冒", "1", "6月龄以上每年接种", "上臂三角肌"}
        };

        for (int i = 0; i < vaccines.length; i++) {
            String[] v = vaccines[i];
            Vaccine vaccine = new Vaccine();
            vaccine.setName(v[0]);
            vaccine.setShortName(v[1]);
            vaccine.setType(v[2]);
            vaccine.setPreventDisease(v[3]);
            vaccine.setDoses(Integer.parseInt(v[4]));
            vaccine.setRecommendAge(v[5]);
            vaccine.setInjectionSite(v[6]);
            vaccine.setDescription("预防" + v[3] + "的疫苗");
            vaccine.setPrecautions("接种前如有发热、急性疾病等情况应暂缓接种");
            vaccine.setAdverseReactions("常见不良反应：接种部位红肿、疼痛，一过性发热等");
            vaccine.setSortOrder(i + 1);
            vaccine.setStatus(1);
            vaccine.setCreateTime(LocalDateTime.now());
            vaccine.setUpdateTime(LocalDateTime.now());
            vaccineMapper.insert(vaccine);

            LocalDate baseDate = baby.getBirthDate();
            LocalDate plannedDate = baseDate.plusMonths(i);
            String status = "pending";
            LocalDate actualDate = null;

            if (i < 3) {
                status = "completed";
                actualDate = baseDate.plusMonths(i);
            }

            BabyVaccine bv = new BabyVaccine();
            bv.setBabyId(babyId);
            bv.setVaccineId(vaccine.getId());
            bv.setDoseNumber(1);
            bv.setPlannedDate(plannedDate);
            bv.setActualDate(actualDate);
            bv.setStatus(status);
            bv.setInjectionSite(v[6]);
            bv.setHospital("市妇幼保健院");
            bv.setBatchNumber(i < 3 ? "BATCH" + (i + 1) : null);
            bv.setAdverseReaction(i == 1 ? "轻微发热，一天后消退" : null);
            bv.setRemark("");
            bv.setRemindEnabled(1);
            bv.setRemindDaysBefore(3);
            bv.setReminded(0);
            bv.setCreateTime(LocalDateTime.now());
            bv.setUpdateTime(LocalDateTime.now());
            babyVaccineMapper.insert(bv);
        }

        String[][] milestones = {
                {"1", "motor", "第一次抬头", "宝宝可以短暂抬头了", "1", "achieved", "第一次抬头，太可爱了！"},
                {"2", "motor", "第一次翻身", "宝宝终于会翻身了，好棒！", "3", "achieved", "动作越来越灵活了"},
                {"3", "motor", "第一次独坐", "宝宝可以自己坐一会儿了", "6", "pending", ""},
                {"4", "motor", "第一次爬行", "宝宝开始满地爬了", "9", "pending", ""},
                {"5", "motor", "第一次站立", "宝宝扶着东西可以站起来了", "10", "pending", ""},
                {"6", "motor", "第一次走路", "宝宝迈出了人生第一步", "12", "pending", ""},
                {"7", "fine_motor", "第一次抓握", "宝宝可以主动抓东西了", "3", "achieved", "小手越来越灵活"},
                {"8", "language", "第一声妈妈", "宝宝终于叫妈妈了，好感动！", "8", "pending", ""},
                {"9", "language", "牙牙学语", "宝宝开始发出咿咿呀呀的声音", "6", "pending", ""},
                {"10", "social", "第一次微笑", "宝宝来到世界上的第一个微笑", "1", "achieved", "笑得好甜！"},
                {"11", "social", "认生", "宝宝开始认生了，见到陌生人会躲", "6", "pending", ""},
                {"12", "fine_motor", "第一颗乳牙", "宝宝长出第一颗小牙了", "6", "achieved", "下门牙冒出来了"}
        };

        for (int i = 0; i < milestones.length; i++) {
            String[] m = milestones[i];
            Milestone milestone = new Milestone();
            milestone.setBabyId(babyId);
            milestone.setMonthAge(Integer.parseInt(m[0]));
            milestone.setCategory(m[1]);
            milestone.setTitle(m[2]);
            milestone.setDescription(m[3]);
            milestone.setStandardMonth(Integer.parseInt(m[4]));
            milestone.setStatus(m[5]);
            milestone.setAchievedDate("achieved".equals(m[5]) ? baby.getBirthDate().plusMonths(Integer.parseInt(m[0])) : null);
            milestone.setNote(m[6]);
            milestone.setCreateTime(LocalDateTime.now());
            milestone.setUpdateTime(LocalDateTime.now());
            milestoneMapper.insert(milestone);
        }

        log.info("宝宝数据初始化完成");
    }

    /**
     * 初始化家庭物品数据
     */
    private void initHouseholdItemData() {
        Long count = householdItemMapper.selectCount(
                new LambdaQueryWrapper<HouseholdItem>().eq(HouseholdItem::getFamilyId, FAMILY_ID)
        );
        if (count > 0) {
            log.info("家庭物品数据已存在，跳过初始化");
            return;
        }

        String[][] items = {
                {"帮宝适尿不湿L号", "diaper", "包", "8", "2", "👶", "宝宝尿不湿，L号"},
                {"飞鹤星飞帆奶粉3段", "milk", "罐", "3", "1", "🍼", "宝宝奶粉，3段"},
                {"抽纸", "daily", "包", "15", "5", "🧻", "家用抽纸"},
                {"洗衣液", "daily", "瓶", "2", "1", "🧴", "婴儿洗衣液"},
                {"婴儿退烧药", "medicine", "瓶", "1", "1", "💊", "布洛芬混悬液"},
                {"宝宝零食", "food", "袋", "6", "2", "🍪", "米饼、溶豆等"},
                {"湿纸巾", "daily", "包", "10", "3", "🫧", "婴儿手口湿巾"},
                {"维生素D3", "medicine", "盒", "2", "1", "💊", "宝宝维生素D3滴剂"}
        };

        Long[] itemIds = new Long[items.length];

        for (int i = 0; i < items.length; i++) {
            String[] itemData = items[i];
            HouseholdItem item = new HouseholdItem();
            item.setFamilyId(FAMILY_ID);
            item.setName(itemData[0]);
            item.setCategory(itemData[1]);
            item.setUnit(itemData[2]);
            item.setTotalQuantity(new BigDecimal(itemData[3]));
            item.setWarningQuantity(new BigDecimal(itemData[4]));
            item.setIcon(itemData[5]);
            item.setRemark(itemData[6]);
            item.setCreateTime(LocalDateTime.now());
            item.setUpdateTime(LocalDateTime.now());
            householdItemMapper.insert(item);
            itemIds[i] = item.getId();
        }

        String[][] records = {
                {"0", "in", "5", "购买", "68.00", "京东购买"},
                {"0", "in", "3", "购买", "68.00", "淘宝购买"},
                {"0", "out", "2", "使用", null, "日常使用"},
                {"1", "in", "2", "购买", "298.00", "母婴店购买"},
                {"1", "in", "2", "购买", "298.00", "京东购买"},
                {"1", "out", "1", "使用", null, "宝宝喝了一罐"},
                {"2", "in", "20", "购买", "5.00", "超市购买"},
                {"2", "out", "5", "使用", null, "日常使用"},
                {"3", "in", "3", "购买", "35.00", "京东购买"},
                {"3", "out", "1", "使用", null, "洗衣服用了一瓶"},
                {"4", "in", "2", "购买", "25.00", "药店购买"},
                {"4", "out", "1", "使用", null, "宝宝发烧用了"},
                {"5", "in", "10", "购买", "15.00", "超市购买"},
                {"5", "out", "4", "使用", null, "宝宝零食"},
                {"6", "in", "15", "购买", "8.00", "京东购买"},
                {"6", "out", "5", "使用", null, "日常使用"},
                {"7", "in", "3", "购买", "45.00", "药店购买"},
                {"7", "out", "1", "使用", null, "每天给宝宝吃"}
        };

        for (String[] r : records) {
            ItemRecord record = new ItemRecord();
            record.setFamilyId(FAMILY_ID);
            record.setItemId(itemIds[Integer.parseInt(r[0])]);
            record.setType(r[1]);
            record.setQuantity(new BigDecimal(r[2]));
            record.setRecordDate(LocalDate.now().minusDays((int)(Math.random() * 30)));
            record.setOperatorId(USER_ID);
            record.setSource(r[3]);
            record.setPrice(r[4] != null ? new BigDecimal(r[4]) : null);
            record.setRemark(r[5]);
            record.setCreateTime(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            itemRecordMapper.insert(record);
        }

        log.info("家庭物品数据初始化完成");
    }
}
