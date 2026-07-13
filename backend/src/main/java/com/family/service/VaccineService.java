package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.entity.Baby;
import com.family.entity.BabyVaccine;
import com.family.entity.Vaccine;
import com.family.mapper.BabyMapper;
import com.family.mapper.BabyVaccineMapper;
import com.family.mapper.VaccineMapper;
import com.family.vo.BabyVaccineVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 疫苗服务
 */
@Service
@RequiredArgsConstructor
public class VaccineService {

    private final VaccineMapper vaccineMapper;
    private final BabyVaccineMapper babyVaccineMapper;
    private final BabyMapper babyMapper;

    /**
     * 获取疫苗列表
     *
     * @param type 疫苗类型（free-免费，paid-自费，all-全部）
     * @return 疫苗列表
     */
    public List<Vaccine> getVaccineList(String type) {
        return vaccineMapper.selectList(
                new LambdaQueryWrapper<Vaccine>()
                        .eq(!"all".equals(type) && type != null && !type.isEmpty(), Vaccine::getType, type)
                        .eq(Vaccine::getStatus, 1)
                        .orderByAsc(Vaccine::getSortOrder)
                        .orderByAsc(Vaccine::getId)
        );
    }

    /**
     * 获取宝宝疫苗计划
     *
     * @param babyId 宝宝ID
     * @param status 状态（pending-待接种，completed-已接种，skipped-已跳过，all-全部）
     * @return 宝宝疫苗列表
     */
    public List<BabyVaccineVO> getBabyVaccines(Long babyId, String status) {
        List<BabyVaccine> babyVaccines = babyVaccineMapper.selectList(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getBabyId, babyId)
                        .eq(!"all".equals(status) && status != null && !status.isEmpty(), BabyVaccine::getStatus, status)
                        .orderByAsc(BabyVaccine::getPlannedDate)
                        .orderByAsc(BabyVaccine::getId)
        );

        if (babyVaccines.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> vaccineIds = babyVaccines.stream()
                .map(BabyVaccine::getVaccineId)
                .distinct()
                .collect(Collectors.toList());

        List<Vaccine> vaccines = vaccineMapper.selectBatchIds(vaccineIds);
        Map<Long, Vaccine> vaccineMap = vaccines.stream()
                .collect(Collectors.toMap(Vaccine::getId, v -> v));

        LocalDate today = LocalDate.now();

        return babyVaccines.stream()
                .map(bv -> convertToVO(bv, vaccineMap.get(bv.getVaccineId()), today))
                .collect(Collectors.toList());
    }

    /**
     * 获取疫苗统计
     *
     * @param babyId 宝宝ID
     * @return 统计信息（已接种数、总数、待接种数）
     */
    public Map<String, Integer> getVaccineStats(Long babyId) {
        List<BabyVaccine> allVaccines = babyVaccineMapper.selectList(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getBabyId, babyId)
        );

        int total = allVaccines.size();
        long completed = allVaccines.stream()
                .filter(bv -> "completed".equals(bv.getStatus()))
                .count();
        long pending = allVaccines.stream()
                .filter(bv -> "pending".equals(bv.getStatus()))
                .count();

        Map<String, Integer> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("completed", (int) completed);
        stats.put("pending", (int) pending);
        return stats;
    }

    /**
     * 标记已接种
     *
     * @param babyId          宝宝ID
     * @param id              记录ID
     * @param hospital        接种医院
     * @param batchNumber     疫苗批号
     * @param adverseReaction 不良反应
     * @param remark          备注
     * @return 更新后的疫苗记录
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyVaccineVO markVaccinated(Long babyId, Long id, String hospital,
                                        String batchNumber, String adverseReaction, String remark) {
        BabyVaccine babyVaccine = babyVaccineMapper.selectOne(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getId, id)
                        .eq(BabyVaccine::getBabyId, babyId)
        );

        if (babyVaccine == null) {
            return null;
        }

        babyVaccine.setStatus("completed");
        babyVaccine.setActualDate(LocalDate.now());
        if (hospital != null) {
            babyVaccine.setHospital(hospital);
        }
        if (batchNumber != null) {
            babyVaccine.setBatchNumber(batchNumber);
        }
        if (adverseReaction != null) {
            babyVaccine.setAdverseReaction(adverseReaction);
        }
        if (remark != null) {
            babyVaccine.setRemark(remark);
        }
        babyVaccine.setUpdateTime(LocalDateTime.now());

        babyVaccineMapper.updateById(babyVaccine);

        Vaccine vaccine = vaccineMapper.selectById(babyVaccine.getVaccineId());
        return convertToVO(babyVaccine, vaccine, LocalDate.now());
    }

    /**
     * 修改计划接种日期
     *
     * @param babyId      宝宝ID
     * @param id          记录ID
     * @param plannedDate 计划接种日期
     * @return 更新后的疫苗记录
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyVaccineVO updatePlannedDate(Long babyId, Long id, LocalDate plannedDate) {
        BabyVaccine babyVaccine = babyVaccineMapper.selectOne(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getId, id)
                        .eq(BabyVaccine::getBabyId, babyId)
        );

        if (babyVaccine == null) {
            return null;
        }

        babyVaccine.setPlannedDate(plannedDate);
        babyVaccine.setReminded(0);
        babyVaccine.setUpdateTime(LocalDateTime.now());

        babyVaccineMapper.updateById(babyVaccine);

        Vaccine vaccine = vaccineMapper.selectById(babyVaccine.getVaccineId());
        return convertToVO(babyVaccine, vaccine, LocalDate.now());
    }

    /**
     * 跳过疫苗
     *
     * @param babyId 宝宝ID
     * @param id     记录ID
     * @param reason 跳过原因
     * @return 更新后的疫苗记录
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyVaccineVO skipVaccine(Long babyId, Long id, String reason) {
        BabyVaccine babyVaccine = babyVaccineMapper.selectOne(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getId, id)
                        .eq(BabyVaccine::getBabyId, babyId)
        );

        if (babyVaccine == null) {
            return null;
        }

        babyVaccine.setStatus("skipped");
        if (reason != null) {
            babyVaccine.setRemark(reason);
        }
        babyVaccine.setUpdateTime(LocalDateTime.now());

        babyVaccineMapper.updateById(babyVaccine);

        Vaccine vaccine = vaccineMapper.selectById(babyVaccine.getVaccineId());
        return convertToVO(babyVaccine, vaccine, LocalDate.now());
    }

    /**
     * 开关提醒
     *
     * @param babyId  宝宝ID
     * @param id      记录ID
     * @param enabled 是否开启（0-关闭，1-开启）
     * @return 更新后的疫苗记录
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyVaccineVO toggleRemind(Long babyId, Long id, Integer enabled) {
        BabyVaccine babyVaccine = babyVaccineMapper.selectOne(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getId, id)
                        .eq(BabyVaccine::getBabyId, babyId)
        );

        if (babyVaccine == null) {
            return null;
        }

        babyVaccine.setRemindEnabled(enabled);
        if (enabled == 1) {
            babyVaccine.setReminded(0);
        }
        babyVaccine.setUpdateTime(LocalDateTime.now());

        babyVaccineMapper.updateById(babyVaccine);

        Vaccine vaccine = vaccineMapper.selectById(babyVaccine.getVaccineId());
        return convertToVO(babyVaccine, vaccine, LocalDate.now());
    }

    /**
     * 根据宝宝出生日期生成标准疫苗计划
     *
     * @param babyId 宝宝ID
     * @return 生成的疫苗计划列表
     */
    @Transactional(rollbackFor = Exception.class)
    public List<BabyVaccineVO> generatePlan(Long babyId) {
        Baby baby = babyMapper.selectById(babyId);
        if (baby == null) {
            return new ArrayList<>();
        }

        Long existingCount = babyVaccineMapper.selectCount(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getBabyId, babyId)
        );
        if (existingCount > 0) {
            return getBabyVaccines(babyId, "all");
        }

        List<Vaccine> vaccines = vaccineMapper.selectList(
                new LambdaQueryWrapper<Vaccine>()
                        .eq(Vaccine::getStatus, 1)
                        .orderByAsc(Vaccine::getSortOrder)
                        .orderByAsc(Vaccine::getId)
        );

        LocalDate birthDate = baby.getBirthDate();
        List<BabyVaccine> babyVaccines = new ArrayList<>();

        for (Vaccine vaccine : vaccines) {
            List<Integer> agesInMonths = parseRecommendAge(vaccine.getRecommendAge());
            int doses = vaccine.getDoses() != null ? vaccine.getDoses() : 1;

            for (int i = 0; i < doses; i++) {
                BabyVaccine bv = new BabyVaccine();
                bv.setBabyId(babyId);
                bv.setVaccineId(vaccine.getId());
                bv.setDoseNumber(i + 1);
                bv.setStatus("pending");
                bv.setInjectionSite(vaccine.getInjectionSite());
                bv.setRemindEnabled(1);
                bv.setRemindDaysBefore(3);
                bv.setReminded(0);
                bv.setCreateTime(LocalDateTime.now());
                bv.setUpdateTime(LocalDateTime.now());

                LocalDate plannedDate;
                if (i < agesInMonths.size()) {
                    int months = agesInMonths.get(i);
                    if (months == 0) {
                        plannedDate = birthDate;
                    } else {
                        plannedDate = birthDate.plusMonths(months);
                    }
                } else {
                    int baseMonths = agesInMonths.isEmpty() ? i : agesInMonths.get(agesInMonths.size() - 1);
                    plannedDate = birthDate.plusMonths(baseMonths + (i - agesInMonths.size() + 1) * 6);
                }
                bv.setPlannedDate(plannedDate);

                babyVaccines.add(bv);
                babyVaccineMapper.insert(bv);
            }
        }

        LocalDate today = LocalDate.now();
        Map<Long, Vaccine> vaccineMap = vaccines.stream()
                .collect(Collectors.toMap(Vaccine::getId, v -> v));

        return babyVaccines.stream()
                .map(bv -> convertToVO(bv, vaccineMap.get(bv.getVaccineId()), today))
                .collect(Collectors.toList());
    }

    /**
     * 获取即将到期的疫苗提醒（用于首页）
     *
     * @return 即将到期的疫苗列表
     */
    public List<BabyVaccineVO> getUpcomingReminders(Long babyId) {
        LocalDate today = LocalDate.now();
        LocalDate sevenDaysLater = today.plusDays(7);

        List<BabyVaccine> babyVaccines = babyVaccineMapper.selectList(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getBabyId, babyId)
                        .eq(BabyVaccine::getStatus, "pending")
                        .eq(BabyVaccine::getRemindEnabled, 1)
                        .between(BabyVaccine::getPlannedDate, today, sevenDaysLater)
                        .orderByAsc(BabyVaccine::getPlannedDate)
        );

        if (babyVaccines.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> vaccineIds = babyVaccines.stream()
                .map(BabyVaccine::getVaccineId)
                .distinct()
                .collect(Collectors.toList());

        List<Vaccine> vaccines = vaccineMapper.selectBatchIds(vaccineIds);
        Map<Long, Vaccine> vaccineMap = vaccines.stream()
                .collect(Collectors.toMap(Vaccine::getId, v -> v));

        return babyVaccines.stream()
                .map(bv -> convertToVO(bv, vaccineMap.get(bv.getVaccineId()), today))
                .collect(Collectors.toList());
    }

    /**
     * 解析推荐接种月龄
     *
     * @param recommendAge 推荐接种月龄字符串
     * @return 月龄列表
     */
    private List<Integer> parseRecommendAge(String recommendAge) {
        List<Integer> ages = new ArrayList<>();
        if (recommendAge == null || recommendAge.isEmpty()) {
            return ages;
        }

        if (recommendAge.contains("出生时")) {
            ages.add(0);
        }

        Pattern pattern = Pattern.compile("(\\d+)月龄");
        Matcher matcher = pattern.matcher(recommendAge);
        while (matcher.find()) {
            ages.add(Integer.parseInt(matcher.group(1)));
        }

        Pattern yearPattern = Pattern.compile("(\\d+)周岁");
        Matcher yearMatcher = yearPattern.matcher(recommendAge);
        while (yearMatcher.find()) {
            ages.add(Integer.parseInt(yearMatcher.group(1)) * 12);
        }

        if (ages.isEmpty()) {
            ages.add(0);
        }

        return ages.stream().distinct().sorted().collect(Collectors.toList());
    }

    /**
     * 转换为VO
     */
    private BabyVaccineVO convertToVO(BabyVaccine bv, Vaccine vaccine, LocalDate today) {
        BabyVaccineVO vo = new BabyVaccineVO();
        vo.setId(bv.getId());
        vo.setVaccineId(bv.getVaccineId());
        vo.setDoseNumber(bv.getDoseNumber());
        vo.setPlannedDate(bv.getPlannedDate());
        vo.setActualDate(bv.getActualDate());
        vo.setStatus(bv.getStatus());
        vo.setInjectionSite(bv.getInjectionSite());
        vo.setHospital(bv.getHospital());
        vo.setBatchNumber(bv.getBatchNumber());
        vo.setAdverseReaction(bv.getAdverseReaction());
        vo.setRemark(bv.getRemark());
        vo.setRemindEnabled(bv.getRemindEnabled());
        vo.setRemindDaysBefore(bv.getRemindDaysBefore());
        vo.setReminded(bv.getReminded());

        if (vaccine != null) {
            vo.setVaccineName(vaccine.getName());
            vo.setShortName(vaccine.getShortName());
            vo.setType(vaccine.getType());
            vo.setTotalDoses(vaccine.getDoses());
            vo.setPreventDisease(vaccine.getPreventDisease());
            vo.setDescription(vaccine.getDescription());
            vo.setPrecautions(vaccine.getPrecautions());
        }

        if (bv.getPlannedDate() != null && "pending".equals(bv.getStatus())) {
            long days = ChronoUnit.DAYS.between(today, bv.getPlannedDate());
            vo.setDaysLeft((int) days);
            vo.setIsOverdue(days < 0);
        } else {
            vo.setDaysLeft(null);
            vo.setIsOverdue(false);
        }

        return vo;
    }
}
