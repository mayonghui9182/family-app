package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.family.entity.TravelGuide;
import com.family.entity.TravelHighlight;
import com.family.mapper.TravelGuideMapper;
import com.family.mapper.TravelHighlightMapper;
import com.family.vo.HotDestinationVO;
import com.family.vo.PageVO;
import com.family.vo.TravelGuideVO;
import com.family.vo.TravelHighlightVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 旅游服务
 */
@Service
@RequiredArgsConstructor
public class TravelService {

    private final TravelGuideMapper travelGuideMapper;
    private final TravelHighlightMapper travelHighlightMapper;

    /**
     * 固定用户ID（模拟）
     */
    private static final Long USER_ID = 1L;

    /**
     * 获取攻略列表（支持搜索、分页）
     *
     * @param keyword  搜索关键词
     * @param current  当前页
     * @param size     每页大小
     * @return 攻略分页列表
     */
    public PageVO<TravelGuideVO> getGuideList(String keyword, Long current, Long size) {
        LambdaQueryWrapper<TravelGuide> wrapper = new LambdaQueryWrapper<TravelGuide>()
                .eq(TravelGuide::getUserId, USER_ID)
                .eq(TravelGuide::getStatus, 1)
                .orderByDesc(TravelGuide::getCreateTime);

        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(TravelGuide::getTitle, keyword)
                    .or().like(TravelGuide::getDestination, keyword));
        }

        Page<TravelGuide> page = travelGuideMapper.selectPage(Page.of(current, size), wrapper);

        List<TravelGuideVO> voList = new ArrayList<>();
        if (page.getRecords() != null && !page.getRecords().isEmpty()) {
            List<Long> guideIds = page.getRecords().stream().map(TravelGuide::getId).collect(Collectors.toList());
            Map<Long, List<TravelHighlight>> highlightMap = getHighlightsByGuideIds(guideIds);

            for (TravelGuide guide : page.getRecords()) {
                TravelGuideVO vo = convertToVO(guide, highlightMap.get(guide.getId()));
                voList.add(vo);
            }
        }

        return new PageVO<>(voList, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /**
     * 获取攻略详情
     *
     * @param id 攻略ID
     * @return 攻略详情
     */
    public TravelGuideVO getGuideDetail(Long id) {
        TravelGuide guide = travelGuideMapper.selectById(id);
        if (guide == null) {
            return null;
        }

        List<TravelHighlight> highlights = travelHighlightMapper.selectList(
                new LambdaQueryWrapper<TravelHighlight>()
                        .eq(TravelHighlight::getGuideId, id)
                        .orderByAsc(TravelHighlight::getSortOrder)
        );

        return convertToVO(guide, highlights);
    }

    /**
     * 获取热门目的地
     *
     * @return 热门目的地列表
     */
    public List<HotDestinationVO> getHotDestinations() {
        List<TravelGuide> guides = travelGuideMapper.selectList(
                new LambdaQueryWrapper<TravelGuide>()
                        .eq(TravelGuide::getUserId, USER_ID)
                        .eq(TravelGuide::getStatus, 1)
        );

        Map<String, List<TravelGuide>> destinationMap = guides.stream()
                .filter(g -> g.getDestination() != null)
                .collect(Collectors.groupingBy(TravelGuide::getDestination));

        return destinationMap.entrySet().stream()
                .map(entry -> {
                    String name = entry.getKey();
                    List<TravelGuide> list = entry.getValue();
                    String cover = list.isEmpty() ? null : list.get(0).getCoverImage();
                    return new HotDestinationVO(name, list.size(), cover);
                })
                .sorted((a, b) -> b.getGuideCount() - a.getGuideCount())
                .limit(6)
                .collect(Collectors.toList());
    }

    /**
     * 批量获取攻略亮点
     */
    private Map<Long, List<TravelHighlight>> getHighlightsByGuideIds(List<Long> guideIds) {
        List<TravelHighlight> highlights = travelHighlightMapper.selectList(
                new LambdaQueryWrapper<TravelHighlight>()
                        .in(TravelHighlight::getGuideId, guideIds)
                        .orderByAsc(TravelHighlight::getSortOrder)
        );
        return highlights.stream().collect(Collectors.groupingBy(TravelHighlight::getGuideId));
    }

    /**
     * 转换为VO
     */
    private TravelGuideVO convertToVO(TravelGuide guide, List<TravelHighlight> highlights) {
        TravelGuideVO vo = new TravelGuideVO();
        vo.setId(guide.getId());
        vo.setTitle(guide.getTitle());
        vo.setDestination(guide.getDestination());
        vo.setStartDate(guide.getStartDate());
        vo.setEndDate(guide.getEndDate());
        vo.setDays(guide.getDays());
        vo.setPeopleCount(guide.getPeopleCount());
        vo.setBudget(guide.getBudget());
        vo.setTransportation(guide.getTransportation());
        vo.setAccommodation(guide.getAccommodation());
        vo.setItinerary(guide.getItinerary());
        vo.setNotes(guide.getNotes());
        vo.setCoverImage(guide.getCoverImage());
        vo.setStatus(guide.getStatus());
        vo.setCreateTime(guide.getCreateTime());

        if (highlights != null && !highlights.isEmpty()) {
            List<TravelHighlightVO> highlightVOS = highlights.stream().map(h -> {
                TravelHighlightVO hvo = new TravelHighlightVO();
                hvo.setId(h.getId());
                hvo.setTitle(h.getTitle());
                hvo.setDescription(h.getDescription());
                hvo.setImageUrl(h.getImageUrl());
                hvo.setLocation(h.getLocation());
                hvo.setDuration(h.getDuration());
                hvo.setSortOrder(h.getSortOrder());
                return hvo;
            }).collect(Collectors.toList());
            vo.setHighlights(highlightVOS);
        }

        return vo;
    }
}
