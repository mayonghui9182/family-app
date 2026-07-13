package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.common.context.UserContext;
import com.family.dto.BabyInfoDTO;
import com.family.dto.GrowthRecordDTO;
import com.family.entity.Baby;
import com.family.entity.BabyVaccine;
import com.family.entity.GrowthRecord;
import com.family.entity.Vaccine;
import com.family.mapper.BabyMapper;
import com.family.mapper.BabyVaccineMapper;
import com.family.mapper.GrowthRecordMapper;
import com.family.mapper.VaccineMapper;
import com.family.vo.BabyInfoVO;
import com.family.vo.BabyVaccineVO;
import com.family.vo.GrowthRecordVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 宝宝服务
 */
@Service
@RequiredArgsConstructor
public class BabyService {

    private final BabyMapper babyMapper;
    private final GrowthRecordMapper growthRecordMapper;
    private final VaccineMapper vaccineMapper;
    private final BabyVaccineMapper babyVaccineMapper;

    /**
     * 获取宝宝信息
     *
     * @param babyId 宝宝ID
     * @return 宝宝信息
     */
    public BabyInfoVO getBabyInfo(Long babyId) {
        Baby baby = babyMapper.selectById(babyId);
        if (baby == null) {
            return null;
        }
        return convertBabyToVO(baby);
    }

    /**
     * 更新宝宝信息
     *
     * @param babyId 宝宝ID
     * @param dto    宝宝信息DTO
     * @return 更新后的宝宝信息
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyInfoVO updateBabyInfo(Long babyId, BabyInfoDTO dto) {
        Baby baby = babyMapper.selectById(babyId);

        if (baby == null) {
            Long userId = UserContext.getUserId();
            baby = new Baby();
            baby.setId(babyId);
            baby.setUserId(userId);
            baby.setName(dto.getName());
            baby.setNickname(dto.getNickname());
            baby.setGender(dto.getGender());
            baby.setBirthDate(dto.getBirthDate());
            baby.setBirthTime(dto.getBirthTime());
            baby.setBirthWeight(dto.getBirthWeight());
            baby.setBirthHeight(dto.getBirthHeight());
            baby.setBloodType(dto.getBloodType());
            baby.setZodiac(dto.getZodiac());
            baby.setConstellation(dto.getConstellation());
            baby.setAvatar(dto.getAvatar());
            baby.setRemark(dto.getRemark());
            baby.setCreateTime(LocalDateTime.now());
            baby.setUpdateTime(LocalDateTime.now());
            babyMapper.insert(baby);
        } else {
            if (dto.getName() != null) {
                baby.setName(dto.getName());
            }
            if (dto.getNickname() != null) {
                baby.setNickname(dto.getNickname());
            }
            if (dto.getGender() != null) {
                baby.setGender(dto.getGender());
            }
            if (dto.getBirthDate() != null) {
                baby.setBirthDate(dto.getBirthDate());
            }
            if (dto.getBirthTime() != null) {
                baby.setBirthTime(dto.getBirthTime());
            }
            if (dto.getBirthWeight() != null) {
                baby.setBirthWeight(dto.getBirthWeight());
            }
            if (dto.getBirthHeight() != null) {
                baby.setBirthHeight(dto.getBirthHeight());
            }
            if (dto.getBloodType() != null) {
                baby.setBloodType(dto.getBloodType());
            }
            if (dto.getZodiac() != null) {
                baby.setZodiac(dto.getZodiac());
            }
            if (dto.getConstellation() != null) {
                baby.setConstellation(dto.getConstellation());
            }
            if (dto.getAvatar() != null) {
                baby.setAvatar(dto.getAvatar());
            }
            if (dto.getRemark() != null) {
                baby.setRemark(dto.getRemark());
            }
            baby.setUpdateTime(LocalDateTime.now());
            babyMapper.updateById(baby);
        }

        return convertBabyToVO(baby);
    }

    /**
     * 获取成长记录列表
     *
     * @param babyId 宝宝ID
     * @param type   记录类型（可选）
     * @return 成长记录列表
     */
    public List<GrowthRecordVO> getGrowthRecords(Long babyId, String type) {
        List<GrowthRecord> records = growthRecordMapper.selectList(
                new LambdaQueryWrapper<GrowthRecord>()
                        .eq(GrowthRecord::getBabyId, babyId)
                        .eq(type != null && !type.isEmpty(), GrowthRecord::getRecordType, type)
                        .orderByDesc(GrowthRecord::getRecordDate)
                        .orderByDesc(GrowthRecord::getCreateTime)
        );

        return records.stream()
                .map(this::convertGrowthRecordToVO)
                .collect(Collectors.toList());
    }

    /**
     * 添加成长记录
     *
     * @param babyId 宝宝ID
     * @param dto    成长记录DTO
     * @return 新增的成长记录
     */
    @Transactional(rollbackFor = Exception.class)
    public GrowthRecordVO addGrowthRecord(Long babyId, GrowthRecordDTO dto) {
        Long userId = UserContext.getUserId();
        LocalDateTime now = LocalDateTime.now();

        GrowthRecord record = new GrowthRecord();
        record.setBabyId(babyId);
        record.setRecordType(dto.getRecordType());
        record.setRecordDate(dto.getRecordDate());
        record.setHeight(dto.getHeight());
        record.setWeight(dto.getWeight());
        record.setMealType(dto.getMealType());
        record.setFoodDesc(dto.getFoodDesc());
        record.setAmount(dto.getAmount());
        record.setSleepDuration(dto.getSleepDuration());
        record.setContent(dto.getContent());
        record.setNote(dto.getNote());
        record.setRecordedBy(userId);
        record.setCreateTime(now);
        record.setUpdateTime(now);

        growthRecordMapper.insert(record);
        return convertGrowthRecordToVO(record);
    }

    /**
     * 删除成长记录
     *
     * @param babyId 宝宝ID
     * @param id     记录ID
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteGrowthRecord(Long babyId, Long id) {
        GrowthRecord record = growthRecordMapper.selectOne(
                new LambdaQueryWrapper<GrowthRecord>()
                        .eq(GrowthRecord::getId, id)
                        .eq(GrowthRecord::getBabyId, babyId)
        );
        if (record == null) {
            return false;
        }
        return growthRecordMapper.deleteById(id) > 0;
    }

    /**
     * 获取疫苗列表
     *
     * @param babyId 宝宝ID
     * @return 疫苗列表
     */
    public List<BabyVaccineVO> getVaccines(Long babyId) {
        List<BabyVaccine> babyVaccines = babyVaccineMapper.selectList(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getBabyId, babyId)
                        .orderByAsc(BabyVaccine::getPlannedDate)
        );

        if (babyVaccines.isEmpty()) {
            return List.of();
        }

        List<Long> vaccineIds = babyVaccines.stream()
                .map(BabyVaccine::getVaccineId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, Vaccine> vaccineMap = vaccineMapper.selectBatchIds(vaccineIds).stream()
                .collect(Collectors.toMap(Vaccine::getId, v -> v));

        return babyVaccines.stream().map(bv -> {
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

            Vaccine vaccine = vaccineMap.get(bv.getVaccineId());
            if (vaccine != null) {
                vo.setVaccineName(vaccine.getName());
                vo.setShortName(vaccine.getShortName());
                vo.setType(vaccine.getType());
                vo.setPreventDisease(vaccine.getPreventDisease());
            }

            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 标记疫苗完成
     *
     * @param babyId 宝宝ID
     * @param id     宝宝疫苗记录ID
     * @return 更新后的疫苗记录
     */
    @Transactional(rollbackFor = Exception.class)
    public BabyVaccineVO toggleVaccine(Long babyId, Long id) {
        BabyVaccine babyVaccine = babyVaccineMapper.selectOne(
                new LambdaQueryWrapper<BabyVaccine>()
                        .eq(BabyVaccine::getId, id)
                        .eq(BabyVaccine::getBabyId, babyId)
        );
        if (babyVaccine == null) {
            return null;
        }

        if ("completed".equals(babyVaccine.getStatus())) {
            babyVaccine.setStatus("pending");
            babyVaccine.setActualDate(null);
        } else {
            babyVaccine.setStatus("completed");
            babyVaccine.setActualDate(LocalDate.now());
        }
        babyVaccine.setUpdateTime(LocalDateTime.now());
        babyVaccineMapper.updateById(babyVaccine);

        return convertBabyVaccineToVO(babyVaccine);
    }

    /**
     * 转换宝宝信息为VO
     */
    private BabyInfoVO convertBabyToVO(Baby baby) {
        BabyInfoVO vo = new BabyInfoVO();
        vo.setId(baby.getId());
        vo.setName(baby.getName());
        vo.setNickname(baby.getNickname());
        vo.setGender(baby.getGender());
        vo.setBirthDate(baby.getBirthDate());
        vo.setBirthTime(baby.getBirthTime());
        vo.setBirthWeight(baby.getBirthWeight());
        vo.setBirthHeight(baby.getBirthHeight());
        vo.setBloodType(baby.getBloodType());
        vo.setZodiac(baby.getZodiac());
        vo.setConstellation(baby.getConstellation());
        vo.setAvatar(baby.getAvatar());
        vo.setRemark(baby.getRemark());
        vo.setCreateTime(baby.getCreateTime());

        if (baby.getBirthDate() != null) {
            Period period = Period.between(baby.getBirthDate(), LocalDate.now());
            vo.setMonthsAge(period.getYears() * 12 + period.getMonths());
        }

        return vo;
    }

    /**
     * 转换成长记录为VO
     */
    private GrowthRecordVO convertGrowthRecordToVO(GrowthRecord record) {
        GrowthRecordVO vo = new GrowthRecordVO();
        vo.setId(record.getId());
        vo.setBabyId(record.getBabyId());
        vo.setRecordType(record.getRecordType());
        vo.setRecordDate(record.getRecordDate());
        vo.setHeight(record.getHeight());
        vo.setWeight(record.getWeight());
        vo.setMealType(record.getMealType());
        vo.setFoodDesc(record.getFoodDesc());
        vo.setAmount(record.getAmount());
        vo.setSleepDuration(record.getSleepDuration());
        vo.setContent(record.getContent());
        vo.setNote(record.getNote());
        vo.setRecordedBy(record.getRecordedBy());
        vo.setCreateTime(record.getCreateTime());
        return vo;
    }

    /**
     * 转换宝宝疫苗为VO
     */
    private BabyVaccineVO convertBabyVaccineToVO(BabyVaccine bv) {
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

        Vaccine vaccine = vaccineMapper.selectById(bv.getVaccineId());
        if (vaccine != null) {
            vo.setVaccineName(vaccine.getName());
            vo.setShortName(vaccine.getShortName());
            vo.setType(vaccine.getType());
            vo.setPreventDisease(vaccine.getPreventDisease());
        }

        return vo;
    }
}
