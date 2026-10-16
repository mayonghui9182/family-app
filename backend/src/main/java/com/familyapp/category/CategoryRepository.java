package com.familyapp.category;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {

    List<Category> findByFamilyIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(Long familyId);

    Optional<Category> findByIdAndFamilyIdAndDeletedAtIsNull(Long id, Long familyId);

    boolean existsByFamilyIdAndNameAndDeletedAtIsNull(Long familyId, String name);

    @Modifying
    @Query("UPDATE Task t SET t.categoryId = :targetId WHERE t.categoryId = :sourceId AND t.deletedAt IS NULL")
    int reassignTasks(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);

    @Query("SELECT COUNT(t) FROM Task t WHERE t.categoryId = :categoryId AND t.deletedAt IS NULL")
    long countTasksByCategoryId(@Param("categoryId") Long categoryId);
}