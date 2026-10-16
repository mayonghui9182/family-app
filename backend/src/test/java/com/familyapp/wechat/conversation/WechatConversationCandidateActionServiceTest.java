package com.familyapp.wechat.conversation;

import com.familyapp.category.Category;
import com.familyapp.category.CategoryRepository;
import com.familyapp.family.FamilyRole;
import com.familyapp.profile.PersonProfile;
import com.familyapp.profile.PersonProfileRepository;
import com.familyapp.profile.ProfileType;
import com.familyapp.task.TaskPriority;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ActionType;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CandidateTask;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.CategoryReference;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.ExperienceContext;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.FamilyContext;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.MemberReference;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotSource;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.SlotValue;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnAction;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnCallResult;
import com.familyapp.wechat.conversation.WechatConversationAgentClient.TurnRequest;
import com.familyapp.wechat.conversation.WechatConversationLeaseService.MessageLease;
import com.familyapp.wechat.conversation.WechatConversationTurnPreparationService.PreparedTurn;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class WechatConversationCandidateActionServiceTest {

    private static final AtomicLong NEXT_ID = new AtomicLong(9_600_000L);
    private static final Instant NOW = Instant.parse("2026-10-02T11:00:00Z");
    private static final String WORKER_ID = "worker-candidate-test";

    @Autowired
    private WechatConversationCandidateActionService service;
    @Autowired
    private WechatChatContextRepository contextRepository;
    @Autowired
    private WechatTaskConversationRepository conversationRepository;
    @Autowired
    private WechatConversationMessageRepository messageRepository;
    @Autowired
    private WechatCandidateVersionRepository candidateRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private PersonProfileRepository profileRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdBindIds = new ArrayList<>();
    private final List<Long> createdReferenceFamilyIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long bindId : createdBindIds) {
            jdbcTemplate.update(
                    "delete from wechat_candidate_versions where conversation_id in "
                            + "(select id from wechat_task_conversations where wechat_bind_id = ?)",
                    bindId);
            jdbcTemplate.update(
                    "delete from wechat_conversation_messages where wechat_bind_id = ?",
                    bindId);
            jdbcTemplate.update(
                    "delete from wechat_task_conversations where wechat_bind_id = ?",
                    bindId);
            jdbcTemplate.update(
                    "delete from wechat_chat_context where wechat_bind_id = ?",
                    bindId);
        }
        for (Long familyId : createdReferenceFamilyIds) {
            jdbcTemplate.update("delete from categories where family_id = ?", familyId);
            jdbcTemplate.update("delete from person_profiles where family_id = ?", familyId);
        }
    }
    @Test
    void presentCreatesNewImmutableVersionAndRetryIsIdempotent() {
        Seed firstSeed = seed();
        TurnCallResult firstResult = present(
                "版本 1：买菜",
                task("买菜"),
                Map.of("title", new SlotValue(
                        "买菜",
                        SlotSource.USER_EXPLICIT,
                        firstSeed.messageId(),
                        null)));

        var first = service.apply(
                firstSeed.lease(),
                turn(firstSeed, 0, List.of(), List.of()),
                firstResult,
                NOW);
        var retry = service.apply(
                firstSeed.lease(),
                turn(firstSeed, 0, List.of(), List.of()),
                firstResult,
                NOW.plusSeconds(1));

        assertThat(first.applied()).isTrue();
        assertThat(first.revision()).isEqualTo(1);
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.candidateVersionId()).isEqualTo(first.candidateVersionId());

        Seed secondSeed = nextMessage(firstSeed, "改成买水果", NOW.plusSeconds(10));
        TurnCallResult secondResult = present(
                "版本 2：买水果",
                task("买水果"),
                Map.of("title", new SlotValue(
                        "买水果",
                        SlotSource.USER_EXPLICIT,
                        secondSeed.messageId(),
                        null)));
        var second = service.apply(
                secondSeed.lease(),
                turn(secondSeed, 1, List.of(), List.of()),
                secondResult,
                NOW.plusSeconds(10));

        assertThat(second.revision()).isEqualTo(2);
        List<WechatCandidateVersion> versions = candidateRepository
                .findByConversationIdAndFamilyIdOrderByRevisionDesc(
                        firstSeed.conversationId(), firstSeed.familyId());
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).getRevision()).isEqualTo(2);
        assertThat(versions.get(0).getCandidatePayload().get(0).get("title"))
                .isEqualTo("买水果");
        assertThat(versions.get(1).getRevision()).isEqualTo(1);
        assertThat(versions.get(1).getCandidatePayload().get(0).get("title"))
                .isEqualTo("买菜");
        assertThat(versions.get(1).getRenderedPreview())
                .contains("1. 买菜", "日期：2026-10-03", "确认创建 1", "尚未创建");
        assertThat(messageRepository.findByIdAndFamilyId(first.replyMessageId(), firstSeed.familyId())
                .orElseThrow().getContent()).isEqualTo(versions.get(1).getRenderedPreview());

        WechatTaskConversation conversation = conversationRepository
                .findByIdAndFamilyId(
                        firstSeed.conversationId(), firstSeed.familyId())
                .orElseThrow();
        assertThat(conversation.getCurrentRevision()).isEqualTo(2);
        assertThat(conversation.getStatus())
                .isEqualTo(ConversationStatus.AWAITING_CONFIRMATION);
        assertThat(outboundCount(firstSeed.bindId())).isEqualTo(2);
    }
    @Test
    void longCandidateReplyIsSplitAtomicallyAndRetryDoesNotDuplicateParts() {
        Seed seed = seed();
        var tasks = java.util.Collections.nCopies(20, task("书".repeat(190)));
        var result = TurnCallResult.accepted(new TurnAction(
                ActionType.PRESENT_CANDIDATE, "请确认下面的任务：", tasks, Map.of()));
        var prepared = turn(seed, 0, List.of(), List.of());
        var first = service.apply(seed.lease(), prepared, result, NOW);
        var retry = service.apply(seed.lease(), prepared, result, NOW.plusSeconds(1));
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.replyMessageId()).isEqualTo(first.replyMessageId());
        var preview = candidateRepository.findByConversationIdAndFamilyIdOrderByRevisionDesc(
                seed.conversationId(), seed.familyId()).get(0).getRenderedPreview();
        var parts = jdbcTemplate.queryForList(
                "select content from wechat_conversation_messages where family_id=? "
                        + "and conversation_id=? and direction='OUTBOUND' order by id", String.class,
                seed.familyId(), seed.conversationId());
        assertThat(parts).hasSizeGreaterThan(1);
        assertThat(String.join("", parts)).isEqualTo(preview);
        assertThat(parts).allSatisfy(part -> assertThat(part.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(1800));
        assertThat(outboundCount(seed.bindId())).isEqualTo(parts.size());
    }

    @Test
    void newCategoryIsOnlyAStoredVisibleProposalBeforeConfirmation() {
        Seed seed = seed();
        var task = new CandidateTask("整理书架", "", LocalDate.of(2026, 10, 17),
                TaskPriority.MEDIUM, null, null, "家庭日常事务");
        var first = service.apply(seed.lease(), turn(seed, 0, List.of(), List.of()),
                present("请核对", task, Map.of()), NOW);
        var candidate = candidateRepository.findByConversationIdAndFamilyIdOrderByRevisionDesc(
                seed.conversationId(), seed.familyId()).get(0);
        assertThat(candidate.getCandidatePayload().get(0).get("category_name")).isEqualTo("家庭日常事务");
        assertThat(candidate.getRenderedPreview()).contains("家庭日常事务（新建，确认后创建）", "确认创建 1");
        assertThat(jdbcTemplate.queryForObject("select count(*) from categories where family_id=? and name=?",
                Integer.class, seed.familyId(), "家庭日常事务")).isZero();
        assertThat(messageRepository.findByIdAndFamilyId(first.replyMessageId(), seed.familyId())
                .orElseThrow().getContent()).isEqualTo(candidate.getRenderedPreview());
    }

    @Test
    void crossFamilyCategoryReferenceIsRejectedWithoutWrites() {
        Seed seed = seed();
        Category foreign = foreignCategory(seed.familyId() + 500);
        CandidateTask task = new CandidateTask(
                "买菜",
                "",
                null,
                TaskPriority.MEDIUM,
                foreign.getId(),
                null);
        TurnCallResult result = present("版本 1", task, Map.of());

        assertThatThrownBy(() -> service.apply(
                seed.lease(),
                turn(
                        seed,
                        0,
                        List.of(new CategoryReference(foreign.getId(), foreign.getName())),
                        List.of()),
                result,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("其他家庭分类");

        assertRolledBack(seed);
    }

    @Test
    void crossFamilyMemberReferenceIsRejectedWithoutWrites() {
        Seed seed = seed();
        PersonProfile foreign = foreignProfile(seed.familyId() + 600);
        CandidateTask task = new CandidateTask(
                "买菜",
                "",
                null,
                TaskPriority.MEDIUM,
                null,
                foreign.getId());
        TurnCallResult result = present("版本 1", task, Map.of());

        assertThatThrownBy(() -> service.apply(
                seed.lease(),
                turn(
                        seed,
                        0,
                        List.of(),
                        List.of(new MemberReference(
                                foreign.getId(),
                                foreign.getName(),
                                foreign.getUserId()))),
                result,
                NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("其他家庭成员");

        assertRolledBack(seed);
    }

    @Test
    void delayedFamilyAResponseIsDiscardedAfterRouteSwitch() throws Exception {
        Seed seed = seed();
        PreparedTurn prepared = turn(seed, 0, List.of(), List.of());
        TurnCallResult result = present("旧家庭候选", task("旧家庭任务"), Map.of());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);

        CompletableFuture<WechatConversationCandidateActionService.ApplyResult> delayed =
                CompletableFuture.supplyAsync(() -> {
                    started.countDown();
                    await(releaseResponse);
                    return service.apply(seed.lease(), prepared, result, NOW);
                });
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        long newFamilyId = seed.familyId() + 1_000;
        long newConversationId = switchFamily(seed, newFamilyId);

        releaseResponse.countDown();
        var applied = delayed.get(5, TimeUnit.SECONDS);

        assertThat(applied.stale()).isTrue();
        assertThat(applied.staleReason())
                .isEqualTo(WechatConversationAiResponseFence.StaleReason.ROUTE_CHANGED);
        assertThat(candidateRepository
                .findByConversationIdAndFamilyIdOrderByRevisionDesc(
                        seed.conversationId(), seed.familyId()))
                .isEmpty();
        assertThat(candidateRepository
                .findByConversationIdAndFamilyIdOrderByRevisionDesc(
                        newConversationId, newFamilyId))
                .isEmpty();
        assertThat(outboundCount(seed.bindId())).isZero();

        WechatConversationMessage source = messageRepository
                .findByIdAndFamilyId(seed.messageId(), seed.familyId())
                .orElseThrow();
        assertThat(source.getProcessingStatus()).isEqualTo(MessageProcessingStatus.SKIPPED);
        assertThat(source.getLastError()).isEqualTo("STALE_AI_RESPONSE:ROUTE_CHANGED");
        WechatChatContext context = contextRepository
                .findByWechatBindId(seed.bindId())
                .orElseThrow();
        assertThat(context.getCurrentFamilyId()).isEqualTo(newFamilyId);
        assertThat(context.getLeaseOwner()).isNull();
    }

    @Test
    void delayedResponseCannotOverwriteNewerCandidate() {
        Seed seed = seed();
        PreparedTurn prepared = turn(seed, 0, List.of(), List.of());
        advanceCandidate(seed);

        TurnCallResult oldResult = present(
                "旧候选", task("不应写入的旧任务"), Map.of());
        var applied = service.apply(
                seed.lease(), prepared, oldResult, NOW.plusSeconds(1));

        assertThat(applied.stale()).isTrue();
        assertThat(applied.staleReason())
                .isEqualTo(WechatConversationAiResponseFence.StaleReason.REVISION_CHANGED);
        List<WechatCandidateVersion> versions = candidateRepository
                .findByConversationIdAndFamilyIdOrderByRevisionDesc(
                        seed.conversationId(), seed.familyId());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getCandidatePayload().get(0).get("title"))
                .isEqualTo("更新后的任务");

        WechatTaskConversation conversation = conversationRepository
                .findByIdAndFamilyId(seed.conversationId(), seed.familyId())
                .orElseThrow();
        assertThat(conversation.getCurrentRevision()).isEqualTo(1);
        assertThat(conversation.getStatus())
                .isEqualTo(ConversationStatus.AWAITING_CONFIRMATION);
        assertThat(outboundCount(seed.bindId())).isZero();

        WechatConversationMessage source = messageRepository
                .findByIdAndFamilyId(seed.messageId(), seed.familyId())
                .orElseThrow();
        assertThat(source.getProcessingStatus()).isEqualTo(MessageProcessingStatus.SKIPPED);
        assertThat(source.getLastError())
                .isEqualTo("STALE_AI_RESPONSE:REVISION_CHANGED");
    }



    private void advanceCandidate(Seed seed) {
        transactionTemplate.executeWithoutResult(transaction -> {
            WechatConversationMessage newer = message(
                    seed.bindId(),
                    seed.userId(),
                    seed.familyId(),
                    seed.conversationId(),
                    "更新后的输入",
                    NOW.plusSeconds(1));
            newer.setProcessingStatus(MessageProcessingStatus.PROCESSED);
            newer.setLeaseOwner(null);
            newer.setLeaseUntil(null);
            newer.setProcessedAt(NOW.plusSeconds(1));
            newer = messageRepository.save(newer);

            WechatCandidateVersion candidate = new WechatCandidateVersion();
            candidate.setConversationId(seed.conversationId());
            candidate.setFamilyId(seed.familyId());
            candidate.setRevision(1);
            candidate.setCandidatePayload(List.of(Map.of("title", "更新后的任务")));
            candidate.setRenderedPreview("更新后的候选");
            candidate.setSourceMessageId(newer.getId());
            candidate.setModelName("test-model");
            candidate.setPromptVersion("test-v1");
            candidate.setExperienceRefs(List.of());
            candidateRepository.save(candidate);

            WechatTaskConversation conversation = conversationRepository
                    .findByIdAndFamilyId(seed.conversationId(), seed.familyId())
                    .orElseThrow();
            conversation.setCurrentRevision(1);
            conversation.setStatus(ConversationStatus.AWAITING_CONFIRMATION);
            conversation.setSlotState(Map.of(
                    "title", Map.of("value", "更新后的任务")));
            conversationRepository.save(conversation);
            entityManager.flush();
        });
    }
    private long switchFamily(Seed seed, long newFamilyId) {
        Long newConversationId = transactionTemplate.execute(transaction -> {
            WechatChatContext context = contextRepository
                    .findByWechatBindId(seed.bindId())
                    .orElseThrow();
            context.setCurrentFamilyId(newFamilyId);
            context.setRouteRevision(1L);
            contextRepository.save(context);

            WechatTaskConversation oldConversation = conversationRepository
                    .findByIdAndFamilyId(seed.conversationId(), seed.familyId())
                    .orElseThrow();
            oldConversation.setStatus(ConversationStatus.PAUSED);
            oldConversation.setPausedFromStatus(ConversationStatus.ACTIVE);
            conversationRepository.save(oldConversation);

            WechatTaskConversation newConversation = new WechatTaskConversation();
            newConversation.setFamilyId(newFamilyId);
            newConversation.setWechatBindId(seed.bindId());
            newConversation.setUserId(seed.userId());
            newConversation.setStatus(ConversationStatus.ACTIVE);
            newConversation.setCurrentRevision(0);
            newConversation.setSlotState(Map.of());
            newConversation.setLastActivityAt(NOW);
            newConversation.setExpiresAt(NOW.plus(1, ChronoUnit.HOURS));
            return conversationRepository.save(newConversation).getId();
        });
        if (newConversationId == null) {
            throw new IllegalStateException("家庭切换测试数据创建失败");
        }
        return newConversationId;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待延迟响应超时");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待延迟响应被中断", exception);
        }
    }
    private void assertRolledBack(Seed seed) {
        WechatTaskConversation conversation = conversationRepository
                .findByIdAndFamilyId(seed.conversationId(), seed.familyId())
                .orElseThrow();
        WechatConversationMessage source = messageRepository
                .findByIdAndFamilyId(seed.messageId(), seed.familyId())
                .orElseThrow();
        assertThat(conversation.getCurrentRevision()).isZero();
        assertThat(conversation.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(source.getProcessingStatus())
                .isEqualTo(MessageProcessingStatus.PROCESSING);
        assertThat(candidateRepository
                .findByConversationIdAndFamilyIdOrderByRevisionDesc(
                        seed.conversationId(), seed.familyId()))
                .isEmpty();
        assertThat(outboundCount(seed.bindId())).isZero();
    }
    private Seed seed() {
        long bindId = NEXT_ID.incrementAndGet();
        long userId = bindId + 100;
        long familyId = bindId + 200;
        createdBindIds.add(bindId);

        Seed seed = transactionTemplate.execute(transaction -> {
            WechatChatContext context = new WechatChatContext();
            context.setWechatBindId(bindId);
            context.setUserId(userId);
            context.setCurrentFamilyId(familyId);
            context.setLeaseOwner(WORKER_ID);
            context.setLeaseUntil(NOW.plusSeconds(120));
            contextRepository.save(context);

            WechatTaskConversation conversation = new WechatTaskConversation();
            conversation.setFamilyId(familyId);
            conversation.setWechatBindId(bindId);
            conversation.setUserId(userId);
            conversation.setStatus(ConversationStatus.ACTIVE);
            conversation.setCurrentRevision(0);
            conversation.setSlotState(Map.of());
            conversation.setLastActivityAt(NOW.minusSeconds(60));
            conversation.setExpiresAt(NOW.plus(1, ChronoUnit.HOURS));
            conversation = conversationRepository.save(conversation);

            WechatConversationMessage source = message(
                    bindId,
                    userId,
                    familyId,
                    conversation.getId(),
                    "请帮我安排买菜",
                    NOW);
            source = messageRepository.save(source);
            entityManager.flush();

            MessageLease lease = new MessageLease(
                    source.getId(),
                    bindId,
                    WORKER_ID,
                    NOW.plusSeconds(120),
                    1);
            return new Seed(
                    bindId,
                    userId,
                    familyId,
                    conversation.getId(),
                    source.getId(),
                    lease);
        });
        if (seed == null) {
            throw new IllegalStateException("测试数据创建失败");
        }
        return seed;
    }

    private Seed nextMessage(Seed previous, String content, Instant now) {
        Seed seed = transactionTemplate.execute(transaction -> {
            WechatChatContext context = contextRepository
                    .findByWechatBindId(previous.bindId())
                    .orElseThrow();
            context.setLeaseOwner(WORKER_ID);
            context.setLeaseUntil(now.plusSeconds(120));
            contextRepository.save(context);

            WechatConversationMessage source = message(
                    previous.bindId(),
                    previous.userId(),
                    previous.familyId(),
                    previous.conversationId(),
                    content,
                    now);
            source = messageRepository.save(source);
            entityManager.flush();
            MessageLease lease = new MessageLease(
                    source.getId(),
                    previous.bindId(),
                    WORKER_ID,
                    now.plusSeconds(120),
                    1);
            return new Seed(
                    previous.bindId(),
                    previous.userId(),
                    previous.familyId(),
                    previous.conversationId(),
                    source.getId(),
                    lease);
        });
        if (seed == null) {
            throw new IllegalStateException("后续测试消息创建失败");
        }
        return seed;
    }

    private WechatConversationMessage message(
            long bindId,
            long userId,
            long familyId,
            long conversationId,
            String content,
            Instant now) {
        WechatConversationMessage source = new WechatConversationMessage();
        source.setWechatAccountId("candidate-test-account");
        source.setExternalMsgId("candidate-message-" + NEXT_ID.incrementAndGet());
        source.setOpenid("candidate-openid-" + bindId);
        source.setDirection(MessageDirection.INBOUND);
        source.setContent(content);
        source.setWechatBindId(bindId);
        source.setUserId(userId);
        source.setFamilyId(familyId);
        source.setConversationId(conversationId);
        source.setProcessingStatus(MessageProcessingStatus.PROCESSING);
        source.setAttempts(1);
        source.setLeaseOwner(WORKER_ID);
        source.setLeaseUntil(now.plusSeconds(120));
        source.setAvailableAt(now);
        source.setReceivedAt(now);
        return source;
    }
    private PreparedTurn turn(
            Seed seed,
            int currentRevision,
            List<CategoryReference> categories,
            List<MemberReference> members) {
        List<ExperienceContext> experiences = List.of(
                new ExperienceContext(77L, 3, "shopping-day", "周五采购"));
        TurnRequest request = new TurnRequest(
                seed.conversationId(),
                0L,
                currentRevision,
                seed.messageId(),
                "请帮我安排买菜",
                new FamilyContext(
                        seed.familyId(),
                        "候选测试家庭",
                        seed.userId(),
                        FamilyRole.ADULT),
                List.of("title", "priority", "notes"),
                Map.of(),
                null,
                null,
                List.of(),
                experiences,
                categories,
                members);
        return new PreparedTurn(
                seed.lease(),
                WechatConversationCommandParser.ParsedCommand.text(
                        "请帮我安排买菜"),
                null,
                seed.userId(),
                seed.familyId(),
                "候选测试家庭",
                seed.conversationId(),
                null,
                request,
                currentRevision == 0
                        ? ConversationStatus.ACTIVE : ConversationStatus.AWAITING_CONFIRMATION,
                null);
    }

    private TurnCallResult present(
            String reply,
            CandidateTask task,
            Map<String, SlotValue> patch) {
        return TurnCallResult.accepted(new TurnAction(
                ActionType.PRESENT_CANDIDATE,
                reply,
                List.of(task),
                patch));
    }

    private CandidateTask task(String title) {
        return new CandidateTask(
                title,
                "",
                LocalDate.of(2026, 10, 3),
                TaskPriority.MEDIUM,
                null,
                null);
    }
    private Category foreignCategory(long familyId) {
        createdReferenceFamilyIds.add(familyId);
        Category category = new Category();
        category.setFamilyId(familyId);
        category.setName("其他家庭分类");
        return categoryRepository.save(category);
    }

    private PersonProfile foreignProfile(long familyId) {
        createdReferenceFamilyIds.add(familyId);
        PersonProfile profile = new PersonProfile();
        profile.setFamilyId(familyId);
        profile.setUserId(familyId + 1);
        profile.setProfileType(ProfileType.ADULT);
        profile.setName("其他家庭成员");
        profile.setAvatarEmoji("👤");
        profile.setRelation("成员");
        profile.setCreatedBy(familyId + 1);
        return profileRepository.save(profile);
    }

    private int outboundCount(long bindId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from wechat_conversation_messages "
                        + "where wechat_bind_id = ? and direction = 'OUTBOUND'",
                Integer.class,
                bindId);
        return count == null ? 0 : count;
    }

    private record Seed(
            long bindId,
            long userId,
            long familyId,
            long conversationId,
            long messageId,
            MessageLease lease) {
    }
}
