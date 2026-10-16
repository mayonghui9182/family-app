package com.familyapp.wechat.conversation;

import com.familyapp.category.CategoryRepository;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.profile.PersonProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 每轮从目标家庭重新选择最多五条有效经验，不缓存家庭上下文。 */
@Service
public class FamilyTaskExperienceSelectionService {
    private final FamilyTaskExperienceRepository experiences;
    private final CategoryRepository categories;
    private final PersonProfileRepository profiles;
    private final FamilyMemberRepository members;

    public FamilyTaskExperienceSelectionService(FamilyTaskExperienceRepository experiences,
            CategoryRepository categories, PersonProfileRepository profiles, FamilyMemberRepository members) {
        this.experiences = experiences;
        this.categories = categories;
        this.profiles = profiles;
        this.members = members;
    }

    @Transactional(readOnly = true)
    public List<FamilyTaskExperience> select(long familyId, String context) {
        String text = FamilyTaskExperienceService.normalize(context == null ? "" : context);
        return experiences.findByFamilyIdAndStatusOrderByUpdatedAtDesc(familyId, ExperienceStatus.ACTIVE)
                .stream().filter(item -> latest(item, familyId))
                .filter(item -> applicable(item, text))
                .filter(item -> validReferences(item, familyId))
                .sorted(Comparator.comparing((FamilyTaskExperience item) ->
                                item.getActivationSource() == ExperienceActivationSource.USER_EXPLICIT).reversed()
                        .thenComparing(Comparator.comparingInt((FamilyTaskExperience item) -> relevance(item, text)).reversed())
                        .thenComparing(FamilyTaskExperience::getConfidence, Comparator.reverseOrder())
                        .thenComparing(FamilyTaskExperience::getUpdatedAt, Comparator.reverseOrder())
                        .thenComparing(FamilyTaskExperience::getId))
                .limit(WechatConversationAgentClient.MAX_EXPERIENCES).toList();
    }

    private boolean latest(FamilyTaskExperience item, long familyId) {
        if (!experiences.findFirstByFamilyIdAndNormalizedKeyOrderByVersionDesc(familyId, item.getNormalizedKey())
                .map(last -> Objects.equals(last.getId(), item.getId())).orElse(false)) return false;
        Object keyword = item.getScopePayload().get("keyword");
        if (item.getActivationSource() == ExperienceActivationSource.TASK_EVIDENCE && keyword instanceof String scope)
            return experiences.findFirstByFamilyIdAndNormalizedKeyOrderByVersionDesc(familyId,
                    "preference:" + FamilyTaskExperienceService.normalize(scope)).isEmpty();
        return true;
    }

    private boolean applicable(FamilyTaskExperience item, String text) {
        Object keyword = item.getScopePayload().get("keyword");
        return keyword == null || (keyword instanceof String word
                && !word.isBlank() && text.contains(FamilyTaskExperienceService.normalize(word)));
    }

    private int relevance(FamilyTaskExperience item, String text) {
        Object keyword = item.getScopePayload().get("keyword");
        return keyword instanceof String word && text.contains(FamilyTaskExperienceService.normalize(word))
                ? word.length() : 0;
    }

    private boolean validReferences(FamilyTaskExperience item, long familyId) {
        var value = item.getValuePayload();
        Object category = value.get("category_id");
        if (category != null && (!(category instanceof Number id)
                || categories.findByIdAndFamilyIdAndDeletedAtIsNull(id.longValue(), familyId).isEmpty())) return false;
        Object profile = value.get("assignee_profile_id");
        if (profile != null && (!(profile instanceof Number id)
                || profiles.findByFamilyIdAndIdAndDeletedAtIsNull(familyId, id.longValue()).isEmpty())) return false;
        Object user = value.get("assignee_id");
        return user == null || (user instanceof Number id
                && members.findByFamilyIdAndUserIdAndLeftAtIsNull(familyId, id.longValue()).isPresent());
    }
}
