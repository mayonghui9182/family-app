package com.family.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.family.common.context.UserContext;
import com.family.entity.HouseholdItem;
import com.family.entity.ItemRecord;
import com.family.entity.User;
import com.family.mapper.HouseholdItemMapper;
import com.family.mapper.ItemRecordMapper;
import com.family.mapper.UserMapper;
import com.family.vo.HouseholdItemVO;
import com.family.vo.ItemRecordVO;
import com.family.vo.ItemStatsVO;
import com.family.vo.PageVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 家庭物品库存服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class HouseholdItemService {

    private final HouseholdItemMapper householdItemMapper;
    private final ItemRecordMapper itemRecordMapper;
    private final UserMapper userMapper;

    private static final Map<String, String> CATEGORY_MAP = new HashMap<>();
    private static final Map<String, String> TYPE_MAP = new HashMap<>();

    static {
        CATEGORY_MAP.put("diaper", "尿不湿");
        CATEGORY_MAP.put("milk", "奶粉");
        CATEGORY_MAP.put("food", "食品");
        CATEGORY_MAP.put("daily", "日用品");
        CATEGORY_MAP.put("medicine", "药品");
        CATEGORY_MAP.put("other", "其他");

        TYPE_MAP.put("in", "入库");
        TYPE_MAP.put("out", "出库");
    }

    /**
     * 获取物品列表，可按分类筛选，按低库存优先排序
     *
     * @param category 分类
     * @return 物品列表
     */
    public List<HouseholdItemVO> getItemList(String category) {
        Long familyId = UserContext.getFamilyId();

        LambdaQueryWrapper<HouseholdItem> wrapper = new LambdaQueryWrapper<HouseholdItem>()
                .eq(HouseholdItem::getFamilyId, familyId)
                .orderByDesc(HouseholdItem::getCreateTime);

        if (category != null && !category.isEmpty()) {
            wrapper.eq(HouseholdItem::getCategory, category);
        }

        List<HouseholdItem> items = householdItemMapper.selectList(wrapper);

        List<HouseholdItemVO> voList = items.stream()
                .map(this::convertToItemVO)
                .collect(Collectors.toList());

        voList.sort((a, b) -> {
            boolean aLow = Boolean.TRUE.equals(a.getIsLowStock());
            boolean bLow = Boolean.TRUE.equals(b.getIsLowStock());
            if (aLow && !bLow) return -1;
            if (!aLow && bLow) return 1;
            return 0;
        });

        return voList;
    }

    /**
     * 获取物品详情
     *
     * @param id 物品ID
     * @return 物品详情
     */
    public HouseholdItemVO getItemDetail(Long id) {
        Long familyId = UserContext.getFamilyId();

        HouseholdItem item = householdItemMapper.selectOne(
                new LambdaQueryWrapper<HouseholdItem>()
                        .eq(HouseholdItem::getId, id)
                        .eq(HouseholdItem::getFamilyId, familyId)
        );

        if (item == null) {
            return null;
        }

        return convertToItemVO(item);
    }

    /**
     * 新增物品
     *
     * @param item 物品信息
     * @return 创建的物品
     */
    @Transactional(rollbackFor = Exception.class)
    public HouseholdItemVO createItem(HouseholdItem item) {
        Long userId = UserContext.getUserId();
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        item.setFamilyId(familyId);
        item.setCreateTime(now);
        item.setUpdateTime(now);
        if (item.getTotalQuantity() == null) {
            item.setTotalQuantity(BigDecimal.ZERO);
        }
        if (item.getWarningQuantity() == null) {
            item.setWarningQuantity(BigDecimal.ZERO);
        }
        if (item.getUnit() == null || item.getUnit().isEmpty()) {
            item.setUnit("个");
        }

        householdItemMapper.insert(item);
        return convertToItemVO(item);
    }

    /**
     * 修改物品
     *
     * @param id   物品ID
     * @param item 物品信息
     * @return 修改后的物品
     */
    @Transactional(rollbackFor = Exception.class)
    public HouseholdItemVO updateItem(Long id, HouseholdItem item) {
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        HouseholdItem existingItem = householdItemMapper.selectOne(
                new LambdaQueryWrapper<HouseholdItem>()
                        .eq(HouseholdItem::getId, id)
                        .eq(HouseholdItem::getFamilyId, familyId)
        );

        if (existingItem == null) {
            return null;
        }

        item.setId(id);
        item.setFamilyId(familyId);
        item.setUpdateTime(now);
        item.setCreateTime(existingItem.getCreateTime());

        householdItemMapper.updateById(item);
        return convertToItemVO(item);
    }

    /**
     * 删除物品（同时删除记录）
     *
     * @param id 物品ID
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteItem(Long id) {
        Long familyId = UserContext.getFamilyId();

        HouseholdItem item = householdItemMapper.selectOne(
                new LambdaQueryWrapper<HouseholdItem>()
                        .eq(HouseholdItem::getId, id)
                        .eq(HouseholdItem::getFamilyId, familyId)
        );

        if (item == null) {
            return false;
        }

        itemRecordMapper.delete(
                new LambdaQueryWrapper<ItemRecord>()
                        .eq(ItemRecord::getItemId, id)
                        .eq(ItemRecord::getFamilyId, familyId)
        );

        return householdItemMapper.deleteById(id) > 0;
    }

    /**
     * 入库（购买等）
     *
     * @param itemId     物品ID
     * @param quantity   数量
     * @param recordDate 记录日期
     * @param source     来源
     * @param price      单价
     * @param remark     备注
     * @return 入库记录
     */
    @Transactional(rollbackFor = Exception.class)
    public ItemRecordVO stockIn(Long itemId, BigDecimal quantity, LocalDate recordDate,
                                String source, BigDecimal price, String remark) {
        Long userId = UserContext.getUserId();
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        HouseholdItem item = householdItemMapper.selectOne(
                new LambdaQueryWrapper<HouseholdItem>()
                        .eq(HouseholdItem::getId, itemId)
                        .eq(HouseholdItem::getFamilyId, familyId)
        );

        if (item == null) {
            return null;
        }

        item.setTotalQuantity(item.getTotalQuantity().add(quantity));
        item.setUpdateTime(now);
        householdItemMapper.updateById(item);

        ItemRecord record = new ItemRecord();
        record.setFamilyId(familyId);
        record.setItemId(itemId);
        record.setType("in");
        record.setQuantity(quantity);
        record.setRecordDate(recordDate != null ? recordDate : LocalDate.now());
        record.setOperatorId(userId);
        record.setSource(source);
        record.setPrice(price);
        record.setRemark(remark);
        record.setCreateTime(now);
        record.setUpdateTime(now);

        itemRecordMapper.insert(record);
        return convertToRecordVO(record, item);
    }

    /**
     * 出库（使用等）
     *
     * @param itemId     物品ID
     * @param quantity   数量
     * @param recordDate 记录日期
     * @param source     用途
     * @param remark     备注
     * @return 出库记录
     */
    @Transactional(rollbackFor = Exception.class)
    public ItemRecordVO stockOut(Long itemId, BigDecimal quantity, LocalDate recordDate,
                                 String source, String remark) {
        Long userId = UserContext.getUserId();
        Long familyId = UserContext.getFamilyId();
        LocalDateTime now = LocalDateTime.now();

        HouseholdItem item = householdItemMapper.selectOne(
                new LambdaQueryWrapper<HouseholdItem>()
                        .eq(HouseholdItem::getId, itemId)
                        .eq(HouseholdItem::getFamilyId, familyId)
        );

        if (item == null) {
            return null;
        }

        if (item.getTotalQuantity().compareTo(quantity) < 0) {
            throw new RuntimeException("库存不足，当前库存：" + item.getTotalQuantity() + item.getUnit());
        }

        item.setTotalQuantity(item.getTotalQuantity().subtract(quantity));
        item.setUpdateTime(now);
        householdItemMapper.updateById(item);

        ItemRecord record = new ItemRecord();
        record.setFamilyId(familyId);
        record.setItemId(itemId);
        record.setType("out");
        record.setQuantity(quantity);
        record.setRecordDate(recordDate != null ? recordDate : LocalDate.now());
        record.setOperatorId(userId);
        record.setSource(source);
        record.setRemark(remark);
        record.setCreateTime(now);
        record.setUpdateTime(now);

        itemRecordMapper.insert(record);
        return convertToRecordVO(record, item);
    }

    /**
     * 获取出入库记录
     *
     * @param itemId 物品ID
     * @param page   页码
     * @param size   每页大小
     * @return 分页记录
     */
    public PageVO<ItemRecordVO> getRecordList(Long itemId, int page, int size) {
        Long familyId = UserContext.getFamilyId();

        LambdaQueryWrapper<ItemRecord> wrapper = new LambdaQueryWrapper<ItemRecord>()
                .eq(ItemRecord::getFamilyId, familyId)
                .orderByDesc(ItemRecord::getRecordDate)
                .orderByDesc(ItemRecord::getCreateTime);

        if (itemId != null) {
            wrapper.eq(ItemRecord::getItemId, itemId);
        }

        Page<ItemRecord> pageParam = new Page<>(page, size);
        Page<ItemRecord> recordPage = itemRecordMapper.selectPage(pageParam, wrapper);

        List<Long> itemIds = recordPage.getRecords().stream()
                .map(ItemRecord::getItemId)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, HouseholdItem> tempItemMap = new HashMap<>();
        if (!itemIds.isEmpty()) {
            List<HouseholdItem> items = householdItemMapper.selectList(
                    new LambdaQueryWrapper<HouseholdItem>()
                            .in(HouseholdItem::getId, itemIds)
                            .eq(HouseholdItem::getFamilyId, familyId)
            );
            tempItemMap = items.stream()
                    .collect(Collectors.toMap(HouseholdItem::getId, item -> item));
        }
        final Map<Long, HouseholdItem> itemMap = tempItemMap;

        List<Long> operatorIds = recordPage.getRecords().stream()
                .map(ItemRecord::getOperatorId)
                .filter(id -> id != null)
                .distinct()
                .collect(Collectors.toList());

        Map<Long, String> tempMap = new HashMap<>();
        if (!operatorIds.isEmpty()) {
            List<User> users = userMapper.selectList(
                    new LambdaQueryWrapper<User>()
                            .in(User::getId, operatorIds)
            );
            tempMap = users.stream()
                    .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : u.getUsername()));
        }
        final Map<Long, String> operatorNameMap = tempMap;

        List<ItemRecordVO> records = recordPage.getRecords().stream()
                .map(record -> {
                    ItemRecordVO vo = convertToRecordVO(record, itemMap.get(record.getItemId()));
                    vo.setOperatorName(operatorNameMap.get(record.getOperatorId()));
                    return vo;
                })
                .collect(Collectors.toList());

        return new PageVO<>(
                records,
                recordPage.getTotal(),
                recordPage.getCurrent(),
                recordPage.getSize(),
                recordPage.getPages()
        );
    }

    /**
     * 获取统计数据
     *
     * @return 统计数据
     */
    public ItemStatsVO getStats() {
        Long familyId = UserContext.getFamilyId();
        LocalDate now = LocalDate.now();
        LocalDate firstDayOfMonth = now.withDayOfMonth(1);

        Long totalItems = householdItemMapper.selectCount(
                new LambdaQueryWrapper<HouseholdItem>()
                        .eq(HouseholdItem::getFamilyId, familyId)
        );

        List<HouseholdItem> allItems = householdItemMapper.selectList(
                new LambdaQueryWrapper<HouseholdItem>()
                        .eq(HouseholdItem::getFamilyId, familyId)
        );

        Long lowStockItems = allItems.stream()
                .filter(item -> item.getTotalQuantity() != null
                        && item.getWarningQuantity() != null
                        && item.getTotalQuantity().compareTo(item.getWarningQuantity()) <= 0)
                .count();

        Map<String, Long> categoryCountMap = allItems.stream()
                .collect(Collectors.groupingBy(
                        item -> item.getCategory() != null ? item.getCategory() : "other",
                        Collectors.counting()
                ));

        List<ItemStatsVO.CategoryStats> categoryStats = new ArrayList<>();
        for (Map.Entry<String, Long> entry : categoryCountMap.entrySet()) {
            ItemStatsVO.CategoryStats stats = new ItemStatsVO.CategoryStats();
            stats.setCategory(entry.getKey());
            stats.setCategoryName(CATEGORY_MAP.getOrDefault(entry.getKey(), "其他"));
            stats.setCount(entry.getValue());
            categoryStats.add(stats);
        }

        List<ItemRecord> monthRecords = itemRecordMapper.selectList(
                new LambdaQueryWrapper<ItemRecord>()
                        .eq(ItemRecord::getFamilyId, familyId)
                        .ge(ItemRecord::getRecordDate, firstDayOfMonth)
        );

        BigDecimal monthlyIn = monthRecords.stream()
                .filter(r -> "in".equals(r.getType()))
                .map(ItemRecord::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal monthlyOut = monthRecords.stream()
                .filter(r -> "out".equals(r.getType()))
                .map(ItemRecord::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        ItemStatsVO statsVO = new ItemStatsVO();
        statsVO.setTotalItems(totalItems);
        statsVO.setLowStockItems(lowStockItems);
        statsVO.setCategories(categoryStats);
        statsVO.setMonthlyIn(monthlyIn);
        statsVO.setMonthlyOut(monthlyOut);

        return statsVO;
    }

    /**
     * 获取低库存物品列表（用于首页提醒）
     *
     * @return 低库存物品列表
     */
    public List<HouseholdItemVO> getLowStockItems() {
        Long familyId = UserContext.getFamilyId();

        List<HouseholdItem> allItems = householdItemMapper.selectList(
                new LambdaQueryWrapper<HouseholdItem>()
                        .eq(HouseholdItem::getFamilyId, familyId)
        );

        return allItems.stream()
                .filter(item -> item.getTotalQuantity() != null
                        && item.getWarningQuantity() != null
                        && item.getTotalQuantity().compareTo(item.getWarningQuantity()) <= 0)
                .map(this::convertToItemVO)
                .collect(Collectors.toList());
    }

    /**
     * 转换物品为VO
     */
    private HouseholdItemVO convertToItemVO(HouseholdItem item) {
        HouseholdItemVO vo = new HouseholdItemVO();
        vo.setId(item.getId());
        vo.setName(item.getName());
        vo.setCategory(item.getCategory());
        vo.setCategoryName(CATEGORY_MAP.getOrDefault(item.getCategory(), "其他"));
        vo.setUnit(item.getUnit());
        vo.setTotalQuantity(item.getTotalQuantity());
        vo.setWarningQuantity(item.getWarningQuantity());
        vo.setIcon(item.getIcon());
        vo.setRemark(item.getRemark());
        vo.setCreateTime(item.getCreateTime());
        vo.setUpdateTime(item.getUpdateTime());

        boolean isLowStock = item.getTotalQuantity() != null
                && item.getWarningQuantity() != null
                && item.getTotalQuantity().compareTo(item.getWarningQuantity()) <= 0;
        vo.setIsLowStock(isLowStock);

        return vo;
    }

    /**
     * 转换记录为VO
     */
    private ItemRecordVO convertToRecordVO(ItemRecord record, HouseholdItem item) {
        ItemRecordVO vo = new ItemRecordVO();
        vo.setId(record.getId());
        vo.setItemId(record.getItemId());
        vo.setType(record.getType());
        vo.setTypeName(TYPE_MAP.getOrDefault(record.getType(), record.getType()));
        vo.setQuantity(record.getQuantity());
        vo.setRecordDate(record.getRecordDate());
        vo.setOperatorId(record.getOperatorId());
        vo.setSource(record.getSource());
        vo.setPrice(record.getPrice());
        vo.setRemark(record.getRemark());
        vo.setCreateTime(record.getCreateTime());

        if (item != null) {
            vo.setItemName(item.getName());
            vo.setUnit(item.getUnit());
        }

        if (record.getPrice() != null && record.getQuantity() != null) {
            vo.setTotalPrice(record.getPrice().multiply(record.getQuantity()));
        }

        return vo;
    }
}
