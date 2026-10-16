package com.familyapp.wechat.conversation;

import com.familyapp.wechat.WechatBind;
import com.familyapp.wechat.WechatBindRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

import static com.familyapp.wechat.conversation.WechatConversationInboundDtos.MessageRequest;
import static com.familyapp.wechat.conversation.WechatConversationInboundDtos.MessageResponse;
import static com.familyapp.wechat.conversation.WechatConversationInboundDtos.PassiveReplyType.BIND_REQUIRED;
import static com.familyapp.wechat.conversation.WechatConversationInboundDtos.PassiveReplyType.RECEIVED;

/**
 * 公众号持续会话的可靠入站服务。
 *
 * <p>事务只覆盖幂等查询、绑定快照和消息插入；不调用 AI、微信 HTTP 或家庭路由。</p>
 */
@Service
public class WechatConversationInboundService {

    private static final Logger LOG =
            LoggerFactory.getLogger(WechatConversationInboundService.class);
    private static final int OPENID_HASH_LENGTH = 16;

    private final WechatConversationMessageRepository messageRepository;
    private final WechatBindRepository bindRepository;
    private final TransactionTemplate transactions;
    private final WechatConversationInboundMetrics metrics;
    private final WechatUndeliveredResultHintService hints;

    public WechatConversationInboundService(
            WechatConversationMessageRepository messageRepository,
            WechatBindRepository bindRepository,
            PlatformTransactionManager transactionManager,
            WechatConversationInboundMetrics metrics,
            WechatUndeliveredResultHintService hints) {
        this.messageRepository = messageRepository;
        this.bindRepository = bindRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.hints = hints;
    }

    public MessageResponse accept(MessageRequest request) {
        long startedAt = System.nanoTime();
        String accountId = request.wechatAccountId().strip();
        String openid = request.openid().strip();
        String externalMessageId = request.msgId().strip();
        String openidHash = hashOpenid(openid);

        try {
            MessageResponse response =
                    acceptPersisted(request, accountId, openid, externalMessageId);
            long elapsedNanos = System.nanoTime() - startedAt;
            metrics.record(response.duplicate(), elapsedNanos);
            LOG.info(
                    "微信会话消息落库 account={} msg_id={} openid_hash={} duplicate={} message_id={} latency_ms={}",
                    accountId,
                    externalMessageId,
                    openidHash,
                    response.duplicate(),
                    response.messageId(),
                    elapsedNanos / 1_000_000);
            return response;
        } catch (RuntimeException failure) {
            long elapsedNanos = System.nanoTime() - startedAt;
            metrics.recordFailure(elapsedNanos);
            LOG.error(
                    "微信会话消息落库失败 account={} msg_id={} openid_hash={} error_type={} latency_ms={}",
                    accountId,
                    externalMessageId,
                    openidHash,
                    failure.getClass().getSimpleName(),
                    elapsedNanos / 1_000_000);
            throw failure;
        }
    }

    private MessageResponse acceptPersisted(
            MessageRequest request,
            String accountId,
            String openid,
            String externalMessageId) {
        try {
            return Objects.requireNonNull(transactions.execute(status ->
                    acceptInTransaction(request, accountId, openid, externalMessageId)));
        } catch (DataIntegrityViolationException duplicateRace) {
            // 两个实例同时通过前置查询时，由数据库唯一键裁决；失败事务结束后读取首次结果。
            return Objects.requireNonNull(transactions.execute(status ->
                    messageRepository.findByWechatAccountIdAndExternalMsgId(accountId, externalMessageId)
                            .map(this::duplicateResponse)
                            .orElseThrow(() -> duplicateRace)));
        }
    }

    private MessageResponse acceptInTransaction(
            MessageRequest request,
            String accountId,
            String openid,
            String externalMessageId) {
        Optional<WechatConversationMessage> existing =
                messageRepository.findByWechatAccountIdAndExternalMsgId(accountId, externalMessageId);
        if (existing.isPresent()) {
            return duplicateResponse(existing.get());
        }

        Optional<WechatBind> bind = bindRepository.findByOpenid(openid);
        WechatConversationMessage message = new WechatConversationMessage();
        message.setWechatAccountId(accountId);
        message.setExternalMsgId(externalMessageId);
        message.setOpenid(openid);
        message.setDirection(MessageDirection.INBOUND);
        // 用户原文是审计证据，禁止 strip 或按旧草稿的 200 字符限制截断。
        message.setContent(request.content());
        message.setReceivedAt(request.receivedAt());
        bind.ifPresent(value -> {
            message.setWechatBindId(value.getId());
            message.setUserId(value.getUser().getId());
        });
        WechatConversationMessage saved = messageRepository.save(message);
        return response(saved, false);
    }

    private MessageResponse duplicateResponse(WechatConversationMessage message) {
        return response(message, true);
    }

    private MessageResponse response(WechatConversationMessage message, boolean duplicate) {
        String hint = hints.hint(message);
        return new MessageResponse(
                message.getId(),
                true,
                duplicate,
                hint != null ? WechatConversationInboundDtos.PassiveReplyType.RESULT_AVAILABLE
                        : message.getWechatBindId() == null ? BIND_REQUIRED : RECEIVED,
                hint);
    }

    static String hashOpenid(String openid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(openid.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, OPENID_HASH_LENGTH);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境不支持 SHA-256", impossible);
        }
    }
}
