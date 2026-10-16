package com.familyapp.calendar;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 日程仓储（Change 13）
 */
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    /** 按家庭查所有未删除日程 */
    List<CalendarEvent> findByFamilyIdAndDeletedAtIsNull(Long familyId);

    /** 按家庭查启用中的日程（调度器用） */
    List<CalendarEvent> findByFamilyIdAndIsActiveTrueAndDeletedAtIsNull(Long familyId);

    /** 查单条（family_id 作用域） */
    Optional<CalendarEvent> findByIdAndFamilyIdAndDeletedAtIsNull(Long id, Long familyId);

    /** 查有启用日程的家庭 ID 列表（调度器逐家庭遍历用） */
    @Query("SELECT DISTINCT e.familyId FROM CalendarEvent e WHERE e.isActive = true AND e.deletedAt IS NULL")
    List<Long> findDistinctFamilyIdsWithActiveEvents();
}
