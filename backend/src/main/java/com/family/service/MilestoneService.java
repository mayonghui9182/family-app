package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.family.entity.Milestone;
import com.family.mapper.MilestoneMapper;
import com.family.vo.MilestoneVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 里程碑服务
 */
@Service
@RequiredArgsConstructor
public class MilestoneService {

    private final MilestoneMapper milestoneMapper;

    /**
     * 获取里程碑列表
     *
     * @param babyId   宝宝ID
     * @param category 分类（可选）
     * @return 里程碑列表
     */
    public List<MilestoneVO> getMilestones(Long babyId, String category) {
        List<Milestone> milestones = milestoneMapper.selectList(
                new LambdaQueryWrapper<Milestone>()
                        .eq(Milestone::getBabyId, babyId)
                        .eq(category != null && !category.isEmpty(), Milestone::getCategory, category)
                        .orderByAsc(Milestone::getMonthAge)
                        .orderByAsc(Milestone::getId)
        );

        return milestones.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    /**
     * 更新里程碑状态
     *
     * @param babyId       宝宝ID
     * @param id           里程碑ID
     * @param status       状态
     * @param achievedDate 达成日期
     * @param note         备注
     * @return 更新后的里程碑
     */
    @Transactional(rollbackFor = Exception.class)
    public MilestoneVO updateStatus(Long babyId, Long id, String status, LocalDate achievedDate, String note) {
        Milestone milestone = milestoneMapper.selectOne(
                new LambdaQueryWrapper<Milestone>()
                        .eq(Milestone::getId, id)
                        .eq(Milestone::getBabyId, babyId)
        );

        if (milestone == null) {
            return null;
        }

        milestone.setStatus(status);
        milestone.setAchievedDate(achievedDate);
        if (note != null) {
            milestone.setNote(note);
        }
        milestone.setUpdateTime(LocalDateTime.now());

        milestoneMapper.updateById(milestone);
        return convertToVO(milestone);
    }

    /**
     * 转换为VO
     */
    private MilestoneVO convertToVO(Milestone milestone) {
        MilestoneVO vo = new MilestoneVO();
        vo.setId(milestone.getId());
        vo.setBabyId(milestone.getBabyId());
        vo.setMonthAge(milestone.getMonthAge());
        vo.setCategory(milestone.getCategory());
        vo.setTitle(milestone.getTitle());
        vo.setDescription(milestone.getDescription());
        vo.setStandardMonth(milestone.getStandardMonth());
        vo.setStatus(milestone.getStatus());
        vo.setAchievedDate(milestone.getAchievedDate());
        vo.setNote(milestone.getNote());
        vo.setCreateTime(milestone.getCreateTime());
        return vo;
    }
}
