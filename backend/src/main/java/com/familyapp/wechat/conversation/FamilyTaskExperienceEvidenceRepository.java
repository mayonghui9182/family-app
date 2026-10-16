package com.familyapp.wechat.conversation;

import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/** 家庭经验的证据仓储；查询始终带 familyId。 */
public interface FamilyTaskExperienceEvidenceRepository
        extends Repository<FamilyTaskExperienceEvidence, Long> {

    <S extends FamilyTaskExperienceEvidence> S save(S entity);

    List<FamilyTaskExperienceEvidence> findByFamilyIdAndSourceTypeAndSourceId(
            Long familyId, ExperienceEvidenceSourceType sourceType, String sourceId);

    Optional<FamilyTaskExperienceEvidence> findByIdAndFamilyId(Long id, Long familyId);

    Optional<FamilyTaskExperienceEvidence> findByFamilyIdAndExperienceIdAndIndependenceKey(
            Long familyId, Long experienceId, String independenceKey);

    List<FamilyTaskExperienceEvidence> findByFamilyIdAndExperienceIdOrderByObservedAtAsc(
            Long familyId, Long experienceId);
}

