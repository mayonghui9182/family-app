package com.familyapp.wechat.conversation;

import com.familyapp.family.Family;
import com.familyapp.family.FamilyMember;
import com.familyapp.family.FamilyMemberRepository;
import com.familyapp.family.FamilyRepository;
import com.familyapp.family.FamilyRole;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WechatConversationRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private WechatChatContextRepository chatContextRepository;

    @Autowired
    private WechatTaskConversationRepository conversationRepository;

    @Autowired
    private WechatConversationMessageRepository messageRepository;

    @Autowired
    private WechatConversationStateService stateService;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private FamilyMemberRepository familyMemberRepository;

    @Autowired
    private WechatCandidateVersionRepository candidateRepository;

    @Test
    void scopedRepositoriesHideObjectsFromOtherFamilies() {
        WechatChatContext context = new WechatChatContext();
        context.setWechatBindId(100L);
        context.setUserId(1L);
        context.setCurrentFamilyId(10L);
        chatContextRepository.save(context);

        WechatTaskConversation conversation = conversationRepository.save(newConversation(10L, 100L));
        WechatConversationMessage message = messageRepository.save(
                newMessage("official-1", "msg-1", 10L, conversation.getId()));
        WechatCandidateVersion candidate = candidateRepository.save(
                newCandidate(10L, conversation.getId(), message.getId(), 1));
        entityManager.flush();
        entityManager.clear();

        assertTrue(chatContextRepository.findByWechatBindId(100L).isPresent());
        assertTrue(conversationRepository.findByIdAndFamilyId(conversation.getId(), 10L).isPresent());
        assertFalse(conversationRepository.findByIdAndFamilyId(conversation.getId(), 20L).isPresent());
        assertTrue(messageRepository.findByIdAndFamilyId(message.getId(), 10L).isPresent());
        assertFalse(messageRepository.findByIdAndFamilyId(message.getId(), 20L).isPresent());
        assertTrue(candidateRepository.findByIdAndFamilyId(candidate.getId(), 10L).isPresent());
        assertFalse(candidateRepository.findByIdAndFamilyId(candidate.getId(), 20L).isPresent());
    }

    @Test
    void onlyOneRecoverableConversationOccupiesActiveSlotPerBindAndFamily() {
        conversationRepository.save(newConversation(10L, 100L));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class, () -> conversationRepository.save(newConversation(10L, 100L)));
    }

    @Test
    void startNewConversationFlushesReleasedSlotBeforeInsert() {
        Long familyId = grantFamilyAccess(1L);
        WechatTaskConversation first =
                stateService.openOrResume(777L, 1L, familyId);
        entityManager.flush();

        WechatTaskConversation second =
                stateService.startNewConversation(777L, 1L, familyId);
        entityManager.flush();

        assertNotEquals(first.getId(), second.getId());
        assertEquals(ConversationStatus.CANCELLED, first.getStatus());
        assertEquals(ConversationStatus.ACTIVE, second.getStatus());
        assertTrue(conversationRepository
                .findByWechatBindIdAndFamilyIdAndActiveSlot(
                        777L,
                        familyId,
                        (short) 1)
                .filter(value -> value.getId().equals(second.getId()))
                .isPresent());
    }

    @Test
    void sameOfficialAccountAndExternalMessageIdIsIdempotent() {
        messageRepository.save(newMessage("official-1", "msg-1", null, null));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class, () -> messageRepository.save(newMessage("official-1", "msg-1", null, null)));
    }

    @Test
    void sameExternalMessageIdFromDifferentOfficialAccountsCanCoexist() {
        messageRepository.save(newMessage("official-1", "msg-shared", null, null));
        messageRepository.save(newMessage("official-2", "msg-shared", null, null));
        entityManager.flush();

        assertTrue(messageRepository
                .findByWechatAccountIdAndExternalMsgId("official-1", "msg-shared").isPresent());
        assertTrue(messageRepository
                .findByWechatAccountIdAndExternalMsgId("official-2", "msg-shared").isPresent());
    }

    @Test
    void familyScopedRepositoriesDoNotExposeBareFindById() {
        assertFalse(hasMethod(WechatTaskConversationRepository.class, "findById"));
        assertFalse(hasMethod(WechatConversationMessageRepository.class, "findById"));
        assertFalse(hasMethod(WechatCandidateVersionRepository.class, "findById"));
    }

    private Long grantFamilyAccess(long userId) {
        Instant now = Instant.now();
        Family family = new Family();
        family.setName("状态机集成测试家庭");
        family.setCreatedBy(userId);
        family.setCreatedAt(now);
        family.setUpdatedAt(now);
        family = familyRepository.save(family);

        FamilyMember member = new FamilyMember();
        member.setFamilyId(family.getId());
        member.setUserId(userId);
        member.setRole(FamilyRole.ADULT);
        member.setJoinedAt(now);
        familyMemberRepository.save(member);
        entityManager.flush();
        return family.getId();
    }

    private boolean hasMethod(Class<?> repositoryType, String methodName) {
        return Arrays.stream(repositoryType.getMethods())
                .anyMatch(method -> method.getName().equals(methodName));
    }

    private WechatTaskConversation newConversation(Long familyId, Long wechatBindId) {
        WechatTaskConversation conversation = new WechatTaskConversation();
        conversation.setFamilyId(familyId);
        conversation.setWechatBindId(wechatBindId);
        conversation.setUserId(1L);
        conversation.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        return conversation;
    }

    private WechatConversationMessage newMessage(
            String accountId, String externalId, Long familyId, Long conversationId) {
        WechatConversationMessage message = new WechatConversationMessage();
        message.setWechatAccountId(accountId);
        message.setExternalMsgId(externalId);
        message.setOpenid("openid-" + accountId);
        message.setDirection(MessageDirection.INBOUND);
        message.setContent("请帮我安排明天买菜");
        message.setFamilyId(familyId);
        message.setConversationId(conversationId);
        return message;
    }

    private WechatCandidateVersion newCandidate(
            Long familyId, Long conversationId, Long sourceMessageId, int revision) {
        WechatCandidateVersion candidate = new WechatCandidateVersion();
        candidate.setFamilyId(familyId);
        candidate.setConversationId(conversationId);
        candidate.setSourceMessageId(sourceMessageId);
        candidate.setRevision(revision);
        candidate.setCandidatePayload(java.util.List.of(
                Map.<String, Object>of("title", "买菜", "status", "PENDING")));
        candidate.setRenderedPreview("1. 买菜");
        candidate.setModelName("test-model");
        candidate.setPromptVersion("test-v1");
        return candidate;
    }
}

