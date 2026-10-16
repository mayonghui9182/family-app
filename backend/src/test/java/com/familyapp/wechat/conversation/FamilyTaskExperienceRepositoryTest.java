package com.familyapp.wechat.conversation;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FamilyTaskExperienceRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private FamilyTaskExperienceRepository experienceRepository;

    @Autowired
    private FamilyTaskExperienceEvidenceRepository evidenceRepository;

    @Test
    void normalizedKeyVersionIsUniqueWithinFamily() {
        experienceRepository.save(newExperience(10L, "assignee:shopping", 1));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class,
                () -> experienceRepository.save(newExperience(10L, "assignee:shopping", 1)));
    }

    @Test
    void sameNormalizedKeyVersionCanExistInDifferentFamilies() {
        FamilyTaskExperience familyA = experienceRepository.save(
                newExperience(10L, "assignee:shopping", 1));
        FamilyTaskExperience familyB = experienceRepository.save(
                newExperience(20L, "assignee:shopping", 1));
        entityManager.flush();

        assertTrue(experienceRepository.findByIdAndFamilyId(familyA.getId(), 10L).isPresent());
        assertTrue(experienceRepository.findByIdAndFamilyId(familyB.getId(), 20L).isPresent());
    }

    @Test
    void sameIndependenceKeyOnlyContributesOnce() {
        FamilyTaskExperience experience = experienceRepository.save(
                newExperience(10L, "due-time:shopping", 1));
        evidenceRepository.save(newEvidence(10L, experience.getId(), "task:100"));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class,
                () -> evidenceRepository.save(newEvidence(10L, experience.getId(), "task:100")));
    }

    @Test
    void experienceAndEvidenceAreInvisibleAcrossFamilies() {
        FamilyTaskExperience experience = experienceRepository.save(
                newExperience(10L, "category:shopping", 1));
        FamilyTaskExperienceEvidence evidence = evidenceRepository.save(
                newEvidence(10L, experience.getId(), "task:101"));
        entityManager.flush();
        entityManager.clear();

        FamilyTaskExperience loaded = experienceRepository
                .findByIdAndFamilyId(experience.getId(), 10L).orElseThrow();
        assertEquals(ExperienceStatus.CANDIDATE, loaded.getStatus());
        assertEquals(1, loaded.getVersion());
        assertFalse(experienceRepository.findByIdAndFamilyId(experience.getId(), 20L).isPresent());
        assertTrue(evidenceRepository.findByIdAndFamilyId(evidence.getId(), 10L).isPresent());
        assertFalse(evidenceRepository.findByIdAndFamilyId(evidence.getId(), 20L).isPresent());
    }

    @Test
    void experienceRepositoriesDoNotExposeBareFindById() {
        assertFalse(hasMethod(FamilyTaskExperienceRepository.class, "findById"));
        assertFalse(hasMethod(FamilyTaskExperienceEvidenceRepository.class, "findById"));
    }

    private boolean hasMethod(Class<?> repositoryType, String methodName) {
        return Arrays.stream(repositoryType.getMethods())
                .anyMatch(method -> method.getName().equals(methodName));
    }

    private FamilyTaskExperience newExperience(Long familyId, String key, int version) {
        FamilyTaskExperience experience = new FamilyTaskExperience();
        experience.setFamilyId(familyId);
        experience.setNormalizedKey(key);
        experience.setValuePayload(Map.of("assignee", "妈妈"));
        experience.setScopePayload(Map.of("keyword", "买菜"));
        experience.setStatus(ExperienceStatus.CANDIDATE);
        experience.setConfidence(new BigDecimal("0.5000"));
        experience.setEvidenceCount(1);
        experience.setVersion(version);
        experience.setActivationSource(ExperienceActivationSource.TASK_EVIDENCE);
        return experience;
    }

    private FamilyTaskExperienceEvidence newEvidence(
            Long familyId, Long experienceId, String independenceKey) {
        FamilyTaskExperienceEvidence evidence = new FamilyTaskExperienceEvidence();
        evidence.setFamilyId(familyId);
        evidence.setExperienceId(experienceId);
        evidence.setSourceType(ExperienceEvidenceSourceType.TASK_OBSERVATION);
        evidence.setSourceId("task-result:100");
        evidence.setIndependenceKey(independenceKey);
        evidence.setContentHash("b".repeat(64));
        return evidence;
    }
}

