package com.familyapp.wechat.conversation;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WechatConfirmationRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private WechatTaskConversationRepository conversationRepository;

    @Autowired
    private WechatConversationMessageRepository messageRepository;

    @Autowired
    private WechatCandidateVersionRepository candidateRepository;

    @Autowired
    private WechatTaskConfirmationRepository confirmationRepository;

    @Autowired
    private WechatConfirmationTaskRepository confirmationTaskRepository;

    @Autowired
    private WechatMessageOutboxRepository outboxRepository;

    @Test
    void candidateVersionCanOnlyBeConfirmedOnce() {
        Fixture fixture = createFixture(10L, 100L, 1);
        confirmationRepository.save(newConfirmation(fixture, "confirm-key-1"));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class,
                () -> confirmationRepository.save(newConfirmation(fixture, "confirm-key-2")));
    }

    @Test
    void confirmationIdempotencyKeyIsUnique() {
        Fixture first = createFixture(10L, 100L, 1);
        Fixture second = createFixture(10L, 101L, 1);
        confirmationRepository.save(newConfirmation(first, "same-confirm-key"));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class,
                () -> confirmationRepository.save(newConfirmation(second, "same-confirm-key")));
    }

    @Test
    void outboxSequenceIsUniqueWithinConversation() {
        Fixture fixture = createFixture(10L, 100L, 1);
        outboxRepository.save(newOutbox(fixture, "candidate:1", 1L));
        entityManager.flush();

        WechatConversationMessage secondMessage = messageRepository.save(
                newMessage(10L, fixture.conversation().getId(), "第二段"));
        WechatMessageOutbox duplicateSequence = newOutbox(
                fixture.conversation().getId(), 10L, secondMessage.getId(), "candidate:2", 1L);

        assertThrows(DataIntegrityViolationException.class,
                () -> outboxRepository.save(duplicateSequence));
    }

    @Test
    void outboxDedupeKeyIsUniqueWithinConversation() {
        Fixture fixture = createFixture(10L, 100L, 1);
        outboxRepository.save(newOutbox(fixture, "candidate:1", 1L));
        entityManager.flush();

        WechatConversationMessage secondMessage = messageRepository.save(
                newMessage(10L, fixture.conversation().getId(), "第二段"));
        WechatMessageOutbox duplicateDedupe = newOutbox(
                fixture.conversation().getId(), 10L, secondMessage.getId(), "candidate:1", 2L);

        assertThrows(DataIntegrityViolationException.class,
                () -> outboxRepository.save(duplicateDedupe));
    }

    @Test
    void confirmationTaskLinkAndOutboxAreInvisibleAcrossFamilies() {
        Fixture fixture = createFixture(10L, 100L, 1);
        WechatTaskConfirmation confirmation = confirmationRepository.save(
                newConfirmation(fixture, "confirm-key-scoped"));
        WechatConfirmationTask taskLink = confirmationTaskRepository.save(
                newTaskLink(10L, confirmation.getId(), 900L));
        WechatMessageOutbox outbox = outboxRepository.save(
                newOutbox(fixture, "result:confirmed", 1L));
        entityManager.flush();
        entityManager.clear();

        assertTrue(confirmationRepository.findByIdAndFamilyId(confirmation.getId(), 10L).isPresent());
        assertFalse(confirmationRepository.findByIdAndFamilyId(confirmation.getId(), 20L).isPresent());
        assertTrue(confirmationTaskRepository.findByIdAndFamilyId(taskLink.getId(), 10L).isPresent());
        assertFalse(confirmationTaskRepository.findByIdAndFamilyId(taskLink.getId(), 20L).isPresent());
        assertTrue(outboxRepository.findByIdAndFamilyId(outbox.getId(), 10L).isPresent());
        assertFalse(outboxRepository.findByIdAndFamilyId(outbox.getId(), 20L).isPresent());
    }

    @Test
    void task13RepositoriesDoNotExposeBareFindById() {
        assertFalse(hasMethod(WechatTaskConfirmationRepository.class, "findById"));
        assertFalse(hasMethod(WechatConfirmationTaskRepository.class, "findById"));
        assertFalse(hasMethod(WechatMessageOutboxRepository.class, "findById"));
    }

    private boolean hasMethod(Class<?> repositoryType, String methodName) {
        return Arrays.stream(repositoryType.getMethods())
                .anyMatch(method -> method.getName().equals(methodName));
    }

    private Fixture createFixture(Long familyId, Long bindId, int revision) {
        WechatTaskConversation conversation = conversationRepository.save(newConversation(familyId, bindId));
        WechatConversationMessage sourceMessage = messageRepository.save(
                newMessage(familyId, conversation.getId(), "请安排买菜"));
        WechatCandidateVersion candidate = candidateRepository.save(
                newCandidate(familyId, conversation.getId(), sourceMessage.getId(), revision));
        WechatConversationMessage outboundMessage = messageRepository.save(
                newMessage(familyId, conversation.getId(), "候选任务"));
        return new Fixture(conversation, candidate, outboundMessage);
    }

    private WechatTaskConversation newConversation(Long familyId, Long bindId) {
        WechatTaskConversation conversation = new WechatTaskConversation();
        conversation.setFamilyId(familyId);
        conversation.setWechatBindId(bindId);
        conversation.setUserId(1L);
        conversation.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        return conversation;
    }

    private WechatConversationMessage newMessage(
            Long familyId, Long conversationId, String content) {
        WechatConversationMessage message = new WechatConversationMessage();
        message.setWechatAccountId("official-test");
        message.setOpenid("openid-test");
        message.setDirection(MessageDirection.OUTBOUND);
        message.setContent(content);
        message.setFamilyId(familyId);
        message.setConversationId(conversationId);
        return message;
    }

    private WechatCandidateVersion newCandidate(
            Long familyId, Long conversationId, Long messageId, int revision) {
        WechatCandidateVersion candidate = new WechatCandidateVersion();
        candidate.setFamilyId(familyId);
        candidate.setConversationId(conversationId);
        candidate.setSourceMessageId(messageId);
        candidate.setRevision(revision);
        candidate.setCandidatePayload(java.util.List.of(
                Map.<String, Object>of("title", "买菜", "status", "PENDING")));
        candidate.setRenderedPreview("1. 买菜");
        candidate.setModelName("test-model");
        candidate.setPromptVersion("test-v1");
        return candidate;
    }

    private WechatTaskConfirmation newConfirmation(Fixture fixture, String idempotencyKey) {
        WechatTaskConfirmation confirmation = new WechatTaskConfirmation();
        confirmation.setConversationId(fixture.conversation().getId());
        confirmation.setFamilyId(fixture.conversation().getFamilyId());
        confirmation.setCandidateVersionId(fixture.candidate().getId());
        confirmation.setCandidateRevision(fixture.candidate().getRevision());
        confirmation.setCandidateHash("a".repeat(64));
        confirmation.setConfirmationMessageId(fixture.outboundMessage().getId());
        confirmation.setConfirmedBy(1L);
        confirmation.setIdempotencyKey(idempotencyKey);
        return confirmation;
    }

    private WechatConfirmationTask newTaskLink(Long familyId, Long confirmationId, Long taskId) {
        WechatConfirmationTask link = new WechatConfirmationTask();
        link.setFamilyId(familyId);
        link.setConfirmationId(confirmationId);
        link.setTaskId(taskId);
        link.setCandidateItemIndex(0);
        link.setInitialTaskSnapshot(Map.of("title", "买菜", "status", "PENDING"));
        return link;
    }

    private WechatMessageOutbox newOutbox(Fixture fixture, String dedupeKey, Long sequenceNo) {
        return newOutbox(
                fixture.conversation().getId(),
                fixture.conversation().getFamilyId(),
                fixture.outboundMessage().getId(),
                dedupeKey,
                sequenceNo);
    }

    private WechatMessageOutbox newOutbox(
            Long conversationId, Long familyId, Long messageId, String dedupeKey, Long sequenceNo) {
        WechatMessageOutbox outbox = new WechatMessageOutbox();
        outbox.setConversationId(conversationId);
        outbox.setFamilyId(familyId);
        outbox.setMessageId(messageId);
        outbox.setDedupeKey(dedupeKey);
        outbox.setSequenceNo(sequenceNo);
        return outbox;
    }

    private record Fixture(
            WechatTaskConversation conversation,
            WechatCandidateVersion candidate,
            WechatConversationMessage outboundMessage) {
    }
}

