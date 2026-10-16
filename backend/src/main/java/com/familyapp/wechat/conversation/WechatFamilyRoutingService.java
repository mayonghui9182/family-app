package com.familyapp.wechat.conversation;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.family.FamilyRole;
import com.familyapp.wechat.WechatBind;
import com.familyapp.wechat.WechatBindRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 微信会话家庭候选和选择解析服务。
 *
 * <p>候选每次都从绑定用户的实时有效成员关系推导，不读取登录缓存，也不签发访问令牌。</p>
 */
@Service
public class WechatFamilyRoutingService {

    static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);
    private static final Pattern NUMBER_SELECTION =
            Pattern.compile("^\\s*(?:第)?(\\d+)(?:个|号)?\\s*$");

    private final WechatBindRepository bindRepository;
    private final FamilyMemberRepository memberRepository;
    private final FamilyRepository familyRepository;
    private final WechatFamilyAccessGuard accessGuard;

    public WechatFamilyRoutingService(
            WechatBindRepository bindRepository,
            FamilyMemberRepository memberRepository,
            FamilyRepository familyRepository,
            WechatFamilyAccessGuard accessGuard) {
        this.bindRepository = bindRepository;
        this.memberRepository = memberRepository;
        this.familyRepository = familyRepository;
        this.accessGuard = accessGuard;
    }

    /**
     * 开始家庭路由：零家庭给出明确状态，单家庭自动选择，多家庭生成五分钟编号挑战。
     */
    @Transactional(readOnly = true)
    public RouteDecision beginSelection(long wechatBindId, long routeRevision, Instant now) {
        Optional<WechatBind> bind = bindRepository.findById(wechatBindId);
        if (bind.isEmpty()) {
            return RouteDecision.withoutSelection(RouteOutcome.NOT_BOUND, List.of(), null);
        }

        List<FamilyOption> options = accessibleFamilies(bind.get());
        if (options.isEmpty()) {
            return RouteDecision.withoutSelection(RouteOutcome.NO_FAMILY, options, null);
        }
        if (options.size() == 1) {
            FamilyOption selected = options.get(0);
            if (!canSelect(bind.get().getUser().getId(), selected.familyId())) {
                return RouteDecision.withoutSelection(
                        RouteOutcome.NO_FAMILY,
                        List.of(),
                        null);
            }
            return RouteDecision.selected(RouteOutcome.AUTO_SELECTED, selected, options);
        }

        FamilySelectionChallenge challenge =
                challenge(wechatBindId, routeRevision, options, now);
        return RouteDecision.withoutSelection(RouteOutcome.CHALLENGE, options, challenge);
    }

    /**
     * 解析最近一次家庭选择挑战；编号只在快照和有效期内可用，名称只在当前有效家庭中匹配。
     */
    @Transactional(readOnly = true)
    public RouteDecision resolveSelection(
            long wechatBindId,
            long routeRevision,
            FamilySelectionChallenge challenge,
            String answer,
            Instant now) {
        if (!challenge.validFor(wechatBindId, routeRevision, now)) {
            return RouteDecision.withoutSelection(RouteOutcome.EXPIRED, List.of(), null);
        }

        Optional<WechatBind> bind = bindRepository.findById(wechatBindId);
        if (bind.isEmpty()) {
            return RouteDecision.withoutSelection(RouteOutcome.NOT_BOUND, List.of(), null);
        }

        List<FamilyOption> current = accessibleFamilies(bind.get());
        Map<Long, FamilyOption> currentById = current.stream()
                .collect(Collectors.toMap(FamilyOption::familyId, Function.identity()));

        Matcher number = NUMBER_SELECTION.matcher(answer == null ? "" : answer);
        if (number.matches()) {
            int index = Integer.parseInt(number.group(1)) - 1;
            if (index < 0 || index >= challenge.candidateFamilyIds().size()) {
                return RouteDecision.withoutSelection(RouteOutcome.NO_MATCH, current, null);
            }
            FamilyOption selected =
                    currentById.get(challenge.candidateFamilyIds().get(index));
            if (selected == null
                    || !canSelect(bind.get().getUser().getId(), selected.familyId())) {
                return RouteDecision.withoutSelection(RouteOutcome.NO_MATCH, current, null);
            }
            return RouteDecision.selected(RouteOutcome.SELECTED, selected, current);
        }

        String normalizedAnswer = normalizeSelectionText(answer);
        List<FamilyOption> matches = current.stream()
                .filter(option -> normalizeName(option.name()).equals(normalizedAnswer))
                .toList();
        if (matches.size() == 1) {
            FamilyOption selected = matches.get(0);
            if (!canSelect(bind.get().getUser().getId(), selected.familyId())) {
                return RouteDecision.withoutSelection(RouteOutcome.NO_MATCH, current, null);
            }
            return RouteDecision.selected(RouteOutcome.SELECTED, selected, current);
        }
        if (matches.size() > 1) {
            FamilySelectionChallenge ambiguousChallenge =
                    challenge(wechatBindId, routeRevision, matches, now);
            return RouteDecision.withoutSelection(
                    RouteOutcome.AMBIGUOUS,
                    matches,
                    ambiguousChallenge);
        }
        return RouteDecision.withoutSelection(RouteOutcome.NO_MATCH, current, null);
    }

    private boolean canSelect(long userId, long familyId) {
        try {
            accessGuard.requireAccess(
                    userId,
                    familyId,
                    WechatFamilyAccessGuard.AccessOperation.SELECT_FAMILY);
            return true;
        } catch (WechatFamilyAccessDeniedException ex) {
            return false;
        }
    }

    /** 自动进入不等同于显式列表挑战；当前、默认、首个有效家庭依次优先。 */
    @Transactional(readOnly = true)
    public RouteDecision automaticSelection(long bindId, Long currentFamilyId, Long defaultFamilyId) {
        var bind = bindRepository.findById(bindId).orElse(null);
        if (bind == null) return RouteDecision.withoutSelection(RouteOutcome.NOT_BOUND, List.of(), null);
        var options = accessibleFamilies(bind);
        for (Long preferred : new Long[] {currentFamilyId, defaultFamilyId}) {
            if (preferred == null) continue;
            var selected = options.stream().filter(item -> preferred.equals(item.familyId()))
                    .filter(item -> canSelect(bind.getUser().getId(), item.familyId())).findFirst();
            if (selected.isPresent()) return RouteDecision.selected(RouteOutcome.AUTO_SELECTED, selected.get(), options);
        }
        var selected = options.stream().filter(item -> canSelect(bind.getUser().getId(), item.familyId()))
                .min(Comparator.comparing(FamilyOption::familyId));
        return selected.map(item -> RouteDecision.selected(RouteOutcome.AUTO_SELECTED, item, options))
                .orElseGet(() -> RouteDecision.withoutSelection(RouteOutcome.NO_FAMILY, List.of(), null));
    }

    /** 默认设置仅接受当前有效家庭的唯一名称，不接受裸家庭 ID。 */
    @Transactional(readOnly = true)
    public Optional<FamilyOption> uniqueFamily(long bindId, String name) {
        var bind = bindRepository.findById(bindId).orElse(null);
        if (bind == null) return Optional.empty();
        var matches = accessibleFamilies(bind).stream()
                .filter(item -> normalizeName(item.name()).equals(normalizeName(name)))
                .filter(item -> canSelect(bind.getUser().getId(), item.familyId())).toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private List<FamilyOption> accessibleFamilies(WechatBind bind) {
        Long userId = bind.getUser().getId();
        List<FamilyMember> memberships =
                memberRepository.findAllByUserIdAndLeftAtIsNull(userId);
        if (memberships.isEmpty()) {
            return List.of();
        }

        Map<Long, FamilyMember> membershipByFamily = memberships.stream()
                .collect(Collectors.toMap(
                        FamilyMember::getFamilyId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Set<Long> allowedFamilyIds = membershipByFamily.keySet();
        List<Family> families =
                familyRepository.findByIdInAndDeletedAtIsNull(allowedFamilyIds);

        List<FamilyOption> options = new ArrayList<>();
        for (Family family : families) {
            FamilyMember membership = membershipByFamily.get(family.getId());
            if (membership == null || family.getDeletedAt() != null) {
                continue;
            }
            FamilyRole role = membership.getRole();
            options.add(new FamilyOption(
                    family.getId(),
                    family.getName(),
                    role,
                    role.getChineseName()));
        }
        options.sort(Comparator
                .comparing((FamilyOption option) -> normalizeName(option.name()))
                .thenComparing(FamilyOption::familyId));
        return List.copyOf(options);
    }

    private FamilySelectionChallenge challenge(
            long wechatBindId,
            long routeRevision,
            List<FamilyOption> options,
            Instant now) {
        return new FamilySelectionChallenge(
                wechatBindId,
                routeRevision,
                now.plus(CHALLENGE_TTL),
                options.stream().map(FamilyOption::familyId).toList());
    }

    private String normalizeSelectionText(String answer) {
        String normalized = answer == null ? "" : answer.strip();
        for (String prefix : List.of("切换到", "切换至", "切换", "选择")) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length());
                break;
            }
        }
        return normalizeName(normalized);
    }

    private String normalizeName(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    public enum RouteOutcome {
        NOT_BOUND,
        NO_FAMILY,
        AUTO_SELECTED,
        CHALLENGE,
        SELECTED,
        EXPIRED,
        AMBIGUOUS,
        NO_MATCH
    }

    public record FamilyOption(
            Long familyId,
            String name,
            FamilyRole role,
            String roleName) {
    }

    public record FamilySelectionChallenge(
            long wechatBindId,
            long routeRevision,
            Instant expiresAt,
            List<Long> candidateFamilyIds) {

        public FamilySelectionChallenge {
            candidateFamilyIds = List.copyOf(candidateFamilyIds);
        }

        boolean validFor(long expectedBindId, long expectedRevision, Instant now) {
            return wechatBindId == expectedBindId
                    && routeRevision == expectedRevision
                    && now.isBefore(expiresAt);
        }
    }

    public record RouteDecision(
            RouteOutcome outcome,
            FamilyOption selected,
            List<FamilyOption> options,
            FamilySelectionChallenge challenge) {

        public RouteDecision {
            options = List.copyOf(options);
        }

        static RouteDecision selected(
                RouteOutcome outcome,
                FamilyOption selected,
                List<FamilyOption> options) {
            return new RouteDecision(outcome, selected, options, null);
        }

        static RouteDecision withoutSelection(
                RouteOutcome outcome,
                List<FamilyOption> options,
                FamilySelectionChallenge challenge) {
            return new RouteDecision(outcome, null, options, challenge);
        }
    }
}
