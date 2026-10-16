package com.familyapp.wechat;

import com.familyapp.agent.AgentCallbackClient;
import com.familyapp.agent.AgentCallbackClient.DraftSubtask;
import com.familyapp.agent.DecomposeSession;
import com.familyapp.agent.DecomposeSessionService;
import com.familyapp.agent.DecomposeSessionType;
import com.familyapp.auth.dto.LoginResponse;
import com.familyapp.family.FamilyRole;
import com.familyapp.family.dto.FamilySummaryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微信 1v1 入站消息受理服务（Change 14 Task 2）。
 *
 * <p>职责边界（design.md D3）：只做「幂等判定 + 绑定校验 + 家庭归属裁决 + 落库」，
 * <b>零 HTTP 外呼、零 LLM 调用</b>，因此受理响应稳定在毫秒级。真正的建草稿与拆解
 * 由 {@code WechatIntakeJob} 异步消费 {@code PENDING} 行完成。
 *
 * <p>裁决顺序是有意为之，不可调换：
 * <ol>
 *   <li>幂等判定最先——否则微信重推会撞 {@code uq_wim_msg_id} 抛 500；</li>
 *   <li>总开关次之，且短路在 {@code loginByOpenid} <b>之前</b>——
 *       该查询会顺带给用户补发 access token，开关关着时不该有这种副作用；</li>
 *   <li>四分支裁决依赖 {@code families[]}，不额外查库、不新增 {@code users} 列。</li>
 * </ol>
 */
@Service
public class WechatIntakeService {

    private static final Logger log = LoggerFactory.getLogger(WechatIntakeService.class);

    /** 与 wechat_inbound_message.content 列宽一致（V12）。 */
    static final int CONTENT_MAX_LENGTH = 200;

    /** skip_reason 契约值（snake_case，wechat-service 据此挑话术，见 design.md D8）。 */
    static final String REASON_DUPLICATE = "duplicate";
    static final String REASON_INTAKE_DISABLED = "intake_disabled";
    static final String REASON_NOT_BOUND = "not_bound";
    static final String REASON_NO_FAMILY = "no_family";
    static final String REASON_ROLE_DENIED = "role_denied";
    static final String REASON_MULTI_FAMILY = "multi_family";

    /** 深链的 OAuth 入口段：真实路由是 wechat-service 的 {@code GET /wechat/authorize}（Task 10 在此读 {@code next}）。 */
    private static final String 授权入口路径 = "/wechat/authorize";
    /** 免密直达的落点前缀，须与 Task 10 的 next 白名单、Task 12 的前端路由逐字一致。 */
    private static final String 草稿页路径前缀 = "/agent/draft/";

    /**
     * 拆解成功话术的前后半句（design.md D8），中间夹深链。<b>链接独占一行</b>：
     * 微信会把行尾紧跟链接的汉字一起吃进 URL，老人一点就打开坏地址。
     *
     * <p>措辞只说「点链接看一眼、确认之后才变成任务」——用户读到这句话时任务还不存在（D5），
     * 不能提前许诺结果。
     */
    private static final String 话术_拆解完成前 =
        "你想办的事，我帮你列好了。点下面这条链接看一眼，觉得合适再点「确认」，任务才会正式建出来：";
    private static final String 话术_拆解完成后 =
        "没去点也没关系，任务不会自己偷偷建。";

    /** 拆解失败话术（design.md D8 逐字）：不解释技术原因，只给一个「怎么说才有用」的例子。 */
    private static final String 话术_拆解失败 =
        "这条我暂时没太看懂，你可以再说具体一点吗？比如「周六上午带爸爸去医院复查」。";

    /** AI 开关关闭话术（design.md D8 逐字）：给出一条眼下就能走的替代路径。 */
    private static final String 话术_功能关闭 =
        "AI 拆解功能暂时关闭了，你可以先在应用里手动添加任务。";

    private final WechatService wechatService;
    private final WechatInboundMessageRepository repository;
    private final DecomposeSessionService decomposeSessionService;
    private final AgentCallbackClient agentCallbackClient;
    private final WechatNotifyClient notifyClient;
    private final String h5BaseUrl;
    private final boolean intakeEnabled;

    public WechatIntakeService(WechatService wechatService,
                               WechatInboundMessageRepository repository,
                               DecomposeSessionService decomposeSessionService,
                               AgentCallbackClient agentCallbackClient,
                               WechatNotifyClient notifyClient,
                               @Value("${wechat.h5-base-url:http://localhost}") String h5BaseUrl,
                               @Value("${wechat.intake.enabled:false}") boolean intakeEnabled) {
        this.wechatService = wechatService;
        this.repository = repository;
        this.decomposeSessionService = decomposeSessionService;
        this.agentCallbackClient = agentCallbackClient;
        this.notifyClient = notifyClient;
        // 运维手填的基址常带尾斜杠，归一后拼接才不会出 https://h5//wechat/... 这种坏链接
        this.h5BaseUrl = h5BaseUrl == null ? "" : h5BaseUrl.strip().replaceAll("/+$", "");
        this.intakeEnabled = intakeEnabled;
    }

    /**
     * 受理结果。{@code reason} 为 null 表示已受理（行落 PENDING，等作业消费）。
     *
     * @param accepted 是否进入拆解队列
     * @param reason   不受理原因（skip_reason 契约值）
     */
    public record Verdict(boolean accepted, String reason) {

        static Verdict ok() {
            return new Verdict(true, null);
        }

        static Verdict rejected(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * 受理一条入站文本。
     *
     * @param openid     微信 openid（仅服务端流转，永不回传前端）
     * @param msgId      微信消息 ID，幂等键
     * @param content    用户原文，超 200 字符截断
     * @param receivedAt 入站时间，为 null 时取当前时间
     */
    @Transactional
    public Verdict accept(String openid, String msgId, String content, Instant receivedAt) {
        if (repository.existsByMsgId(msgId)) {
            log.debug("入站消息重复，忽略：msgId={}", msgId);
            return Verdict.rejected(REASON_DUPLICATE);
        }

        Instant now = receivedAt == null ? Instant.now() : receivedAt;
        String text = normalize(content);

        if (!intakeEnabled) {
            return 落不受理行(msgId, openid, text, now, REASON_INTAKE_DISABLED);
        }

        Optional<LoginResponse> login = wechatService.loginByOpenid(openid);
        if (login.isEmpty()) {
            return 落不受理行(msgId, openid, text, now, REASON_NOT_BOUND);
        }

        List<FamilySummaryResponse> families = login.get().families();
        if (families == null || families.isEmpty()) {
            return 落不受理行(msgId, openid, text, now, REASON_NO_FAMILY);
        }
        // 多家庭时任何自动选择都是赌，赌错即违反 family_id 硬隔离 ⇒ 宁拒不猜
        if (families.size() >= 2) {
            return 落不受理行(msgId, openid, text, now, REASON_MULTI_FAMILY);
        }

        FamilySummaryResponse family = families.get(0);
        // CHILD 提前判掉：agent 侧只放 OWNER/ADULT，与其让作业吃 40302 白跑一趟，不如此处拒
        if (family.role() == FamilyRole.CHILD) {
            return 落不受理行(msgId, openid, text, now, REASON_ROLE_DENIED);
        }

        WechatInboundMessage row = newRow(msgId, openid, text, now);
        row.setStatus(WechatInboundStatus.PENDING);
        row.setFamilyId(family.id());
        row.setUserId(login.get().userId());
        repository.save(row);
        log.info("入站消息已受理：msgId={} familyId={} userId={}", msgId, family.id(), row.getUserId());
        return Verdict.ok();
    }

    /**
     * 由 {@code WechatIntakeJob} 调用：把一条已受理的入站消息变成 AI 拆解草稿（Change 14 Task 4）。
     *
     * <p>落库完全复用既有 D-1 {@code createDraft}（Change 10 的「草稿箱-原子确认」模式），不另造一条写库路径：
     * 只写 {@code agent_decompose_sessions}，<b>在用户于 H5 草稿页确认之前，{@code tasks} 表一行不写</b>。
     *
     * <p>原文放在 {@code init_payload.title} 而非 {@code messages}：{@code messages} 是全仓从未真正写入过内容的
     * 死列（只有 {@code createDraft} 那一句 {@code new ArrayList<>()}），给它引入新语义等于凭空发明契约（D5）。
     * 该列宽 200，与受理侧 {@link #CONTENT_MAX_LENGTH} 一致，两侧各截断一次属有意冗余。
     *
     * <p>事务边界：本方法<b>不加</b> {@code @Transactional}。作业是在事务外调用它的（design.md D2
     * 「事务绝不跨外呼」），事务由 {@code createDraft} 自身承担，只覆盖这一次建草稿。
     *
     * @param row 已抢占为 RUNNING 的入站行，familyId/userId 由受理侧裁决写入
     * @return 新建草稿的 session_id，供作业回写入站行并拼进回推深链
     */
    public Long createDraftFromMessage(WechatInboundMessage row) {
        DecomposeSession draft = decomposeSessionService.createDraft(
            DecomposeSessionType.TASK_CREATE.name(),
            Map.of("title", row.getContent()),
            row.getFamilyId(),
            row.getUserId());
        log.info("入站消息已建拆解草稿：msgId={} sessionId={} familyId={}",
            row.getMsgId(), draft.getId(), draft.getFamilyId());
        return draft.getId();
    }

    /**
     * 由 {@code WechatIntakeJob} 调用：把一条已受理的入站消息走完「建草稿 + 触发 A-2 拆解」（Task 6b）。
     *
     * <p>三步次序是硬约束，不可调换：
     * <ol>
     *   <li><b>先取票据</b>——绑定可能在受理与作业之间被解绑，先失败可以留一个干净的空档，
     *       而不是留下一堆没人认领的空草稿；</li>
     *   <li><b>再确定草稿</b>——行上已有 {@code session_id} 就复用（回队重跑），没有才新建并<b>立刻中转</b>
     *       回落库；漏掉这一步，一条消息在失败重试后会长出多份草稿，用户就会收到多条推送；</li>
     *   <li><b>最后外呼</b>——A-2 的拆解结果由 agent-service 自己写进 {@code proposed_tasks}，
     *       backend 侧只触发并校验非空，仍然一行 {@code tasks} 都不写（D5）。</li>
     * </ol>
     *
     * <p>失败一律原样上抛，不吞不转译：重试额度与终态（回 {@code PENDING} 还是 {@code FAILED}）
     * 归 {@code WechatIntakeJob} 判定（D8）。
     *
     * <p>事务边界同 {@link #createDraftFromMessage}：本方法<b>不加</b> {@code @Transactional}，
     * 因为 A-2 是数十秒级外呼，事务绝不跨其上（D2）。落库的原子性由 {@code createDraft} 与
     * {@code attachSession} 各自的短事务承担。
     *
     * @param row 已抢占为 RUNNING 的入站行，familyId/userId 由受理侧裁决写入
     * @return 本次拆解所挂靠的草稿 session_id
     */
    public Long decomposeInbound(WechatInboundMessage row) {
        String accessToken = 取票据(row);
        Long sessionId = 确定草稿(row);
        // 带 null 外呼等于让 agent-service 走 B-1 直建任务链路，直接破 D5 铁律 ⇒ 硬失败，不降级
        if (sessionId == null) {
            throw new IllegalStateException("入站消息未能确定拆解草稿，中止拆解以免退化为直接建任务：msgId="
                + row.getMsgId());
        }

        List<DraftSubtask> subtasks = agentCallbackClient.decomposeDraft(
            row.getFamilyId(), accessToken, row.getContent(), sessionId);
        if (subtasks == null || subtasks.isEmpty()) {
            throw new IllegalStateException("AI 拆解未返回任何子任务，草稿仍为空白，按失败处理：sessionId=" + sessionId);
        }
        log.info("入站消息拆解成功：msgId={} sessionId={} 子任务数={}", row.getMsgId(), sessionId, subtasks.size());
        return sessionId;
    }

    /**
     * 拆解成功 → 回推免密深链（Change 14 Task 8，design.md D6/D7）。
     *
     * <p>链接形态 {@code {h5}/wechat/authorize?next=/agent/draft/{id}}：先过 wechat-service 的
     * OAuth 入口换免密登录态，再由 {@code next} 直达 H5 草稿详情页（Task 10 加白名单、Task 12 建页面）。
     *
     * @param sessionId 草稿 session_id；为 null 表示没建出可确认的草稿，此时<b>宁可不推</b>
     */
    public void notifyDone(WechatInboundMessage row, Long sessionId) {
        if (sessionId == null) {
            log.warn("拆解成功但没有草稿指针，跳过回推（用户可在应用内查看）：msgId={}", row.getMsgId());
            return;
        }
        推送(row, 话术_拆解完成前 + "\n" + 草稿链接(sessionId) + "\n" + 话术_拆解完成后, "确认链接");
    }

    /** 拆解失败且重试耗尽 → 回推不含链接的中文兜底话术（D8：只说没说懂，不报错码）。 */
    public void notifyFailed(WechatInboundMessage row) {
        推送(row, 话术_拆解失败, "兜底话术");
    }

    /** AI 功能开关关闭 → 回推「暂时关闭」并指一条手动添加的替代路（D8 明写要回推，不让用户干等）。 */
    public void notifyAiDisabled(WechatInboundMessage row) {
        推送(row, 话术_功能关闭, "关闭提示");
    }

    /**
     * 回推的统一出口：缺 openid 就静默，其余异常一律在本层收口。
     *
     * <p>{@link WechatNotifyClient} 本身已经永不抛出，这里再兜一层是防「未来有人给客户端加了会抛的分支」
     * 把通知旁路升级成主链路故障——spec.md 的硬条款是「回推失败 SHALL NOT 改变入站行的终态」。
     */
    private void 推送(WechatInboundMessage row, String content, String 类型) {
        String openid = row.getOpenid();
        if (openid == null || openid.isBlank()) {
            log.warn("入站消息缺少 openid，无法回推{}：msgId={}", 类型, row.getMsgId());
            return;
        }
        try {
            if (!notifyClient.sendKefuText(openid, content)) {
                log.warn("回推{}未送达（微信侧拒绝或推送开关关闭），草稿与入站行状态不变：msgId={}",
                        类型, row.getMsgId());
                return;
            }
            log.info("回推{}成功：msgId={}", 类型, row.getMsgId());
        } catch (RuntimeException e) {
            log.warn("回推{}异常（已隔离，不影响拆解终态）：msgId={} error={}", 类型, row.getMsgId(), e.toString());
        }
    }

    /** 免密深链：design.md D7 行文的 {@code /wechat/oauth} 全仓不存在，实际路由是 {@code /wechat/authorize}。 */
    private String 草稿链接(Long sessionId) {
        return h5BaseUrl + 授权入口路径 + "?next=" + 草稿页路径前缀 + sessionId;
    }

    /** 取用户级 access token：绑定失效或票据为空都在建草稿之前失败。 */
    private String 取票据(WechatInboundMessage row) {
        LoginResponse login = wechatService.loginByOpenid(row.getOpenid())
            .orElseThrow(() -> new IllegalStateException(
                "该微信的公众号绑定已失效，请在公众号里重新绑定后再发消息：msgId=" + row.getMsgId()));
        if (login.accessToken() == null || login.accessToken().isBlank()) {
            throw new IllegalStateException(
                "取不到登录票据（账号可能刚被退出登录），请在公众号里重新绑定：msgId=" + row.getMsgId());
        }
        return login.accessToken();
    }

    /** 复用行上草稿，或新建一份并立刻把 session_id 中转回入站行。 */
    private Long 确定草稿(WechatInboundMessage row) {
        if (row.getSessionId() != null) {
            log.info("入站消息复用已有草稿：msgId={} sessionId={}", row.getMsgId(), row.getSessionId());
            return row.getSessionId();
        }
        Long sessionId = createDraftFromMessage(row);
        if (sessionId != null) {
            repository.attachSession(row.getMsgId(), sessionId, Instant.now());
            row.setSessionId(sessionId);
        }
        return sessionId;
    }

    private Verdict 落不受理行(String msgId, String openid, String text,
                               Instant receivedAt, String reason) {
        WechatInboundMessage row = newRow(msgId, openid, text, receivedAt);
        row.setStatus(WechatInboundStatus.SKIPPED);
        row.setSkipReason(reason);
        repository.save(row);
        log.info("入站消息不受理：msgId={} reason={}", msgId, reason);
        return Verdict.rejected(reason);
    }

    private WechatInboundMessage newRow(String msgId, String openid, String text, Instant receivedAt) {
        WechatInboundMessage row = new WechatInboundMessage();
        row.setMsgId(msgId);
        row.setOpenid(openid);
        row.setContent(text);
        row.setReceivedAt(receivedAt);
        row.setAttempts(0);
        return row;
    }

    /** 去首尾空白 + 截断到列宽，避免超长文本让整条入库失败。 */
    private String normalize(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.strip();
        return trimmed.length() <= CONTENT_MAX_LENGTH
            ? trimmed
            : trimmed.substring(0, CONTENT_MAX_LENGTH);
    }
}
