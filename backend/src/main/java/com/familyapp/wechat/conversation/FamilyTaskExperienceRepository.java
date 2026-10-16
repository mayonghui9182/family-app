package com.familyapp.wechat.conversation;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** 家庭长期任务经验仓储；所有读取必须显式携带 familyId。 */
public interface FamilyTaskExperienceRepository extends Repository<FamilyTaskExperience, Long> {

    <S extends FamilyTaskExperience> S save(S entity);

    Optional<FamilyTaskExperience> findFirstByFamilyIdAndNormalizedKeyOrderByVersionDesc(
            Long familyId, String normalizedKey);

    List<FamilyTaskExperience> findByFamilyIdAndNormalizedKeyOrderByVersionDesc(
            Long familyId, String normalizedKey);

    Optional<FamilyTaskExperience> findByIdAndFamilyId(Long id, Long familyId);

    Optional<FamilyTaskExperience> findByFamilyIdAndNormalizedKeyAndVersion(
            Long familyId, String normalizedKey, Integer version);

    List<FamilyTaskExperience> findByFamilyIdAndStatusOrderByUpdatedAtDesc(
            Long familyId, ExperienceStatus status);
}

