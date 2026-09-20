package com.matrix.agent.voice;

import android.util.Log;
import android.os.SystemClock;

import com.matrix.agent.conversation.ConversationCoordinator;
import com.matrix.agent.conversation.ConversationDomain.ConversationInputChannel;
import com.matrix.agent.conversation.ConversationIds;
import com.matrix.agent.conversation.ConversationStore.MessageRow;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.InputSource;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.conversation.ConversationTaskSubmitter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Voice → Conversation 的进程内桥（设计文档 §6.4 唯一提交点 + §7.1 受控回注）。
 *
 * <p>提交侧（VoiceSessionController.consumeFinal accepted 分支调用）：
 * 把 ASR final + 会话上下文封装为 {@link SubmitRequest}，在 Controller 的
 * agentExecutor 上异步投递给 {@link ConversationCoordinator}——事务完成后经
 * {@link ReceiptListener} 回投 Controller 驱动 SUBMITTING→THINKING / 失败收敛。</p>
 *
 * <p>回注侧（Coordinator 终态回调）：校验 {@link VoiceResponseToken} 四元组
 * 后调用 {@link TerminalListener}，由 Controller 复用既有 onTerminal 内部路径
 * （ResponsePresenter → focus → speakNow → utteranceId/watchdog）。</p>
 */
public final class VoiceConversationBridge {

    private static final String TAG = "MatrixAgent";
    /** 提交段上限（设计文档 §7.1 前置持久化预算）：含落库事务 + 订阅分发 + lane 预订。 */
    public static final long SUBMIT_BUDGET_MS = 8_000L;

    /** Controller → Bridge 的提交请求（§6.4）。 */
    public record SubmitRequest(
            String text,
            String languageTag,
            float asrConfidence,
            boolean confidenceAvailable,
            int audioZoneId,
            VoiceResponseToken token) { }

    /** Controller 生成的不透明回注令牌（§7.1）。 */
    public record VoiceResponseToken(
            String voiceSessionId,
            long generation,
            String requestNonce,
            long acceptedAtElapsedMs) { }

    /** Bridge → Controller 的提交结果回投。 */
    public interface ReceiptListener {
        void onSubmissionAccepted(VoiceResponseToken token);
        void onSubmissionFailed(VoiceResponseToken token, String errorCode);
    }

    /** Bridge → Controller 的终态回注（token 匹配后才调用）。 */
    public interface TerminalListener {
        void onConversationTerminal(VoiceResponseToken token, AgentOutcome outcome);
    }

    private final ConversationCoordinator coordinator;
    private final ReceiptListener receiptListener;
    private final TerminalListener terminalListener;
    private final String agentSessionPrefix;
    private final AtomicLong nonceSeq = new AtomicLong();
    /** 进程内临时关联；进程死后不会尝试 TTS 回放，恢复对账接管持久化消息。 */
    private final ConcurrentHashMap<String, VoiceResponseToken> tokensByTaskId =
            new ConcurrentHashMap<>();

    public VoiceConversationBridge(ConversationCoordinator coordinator,
            ReceiptListener receiptListener, TerminalListener terminalListener,
            String agentSessionPrefix) {
        this.coordinator = coordinator;
        this.receiptListener = receiptListener;
        this.terminalListener = terminalListener;
        this.agentSessionPrefix = agentSessionPrefix;
        coordinator.addTerminalListener((taskId, outcome) -> {
            VoiceResponseToken token = tokensByTaskId.remove(taskId);
            if (token != null) deliverTerminal(token, outcome);
        });
    }

    /**
     * 生成新 token（Controller 在 consumeFinal accepted 分支调用）。
     * requestNonce 保证同一 generation 内不重复。
     */
    public VoiceResponseToken newToken(String voiceSessionId, long generation) {
        return new VoiceResponseToken(voiceSessionId, generation,
                "vn-" + nonceSeq.incrementAndGet(), SystemClock.elapsedRealtime());
    }

    /**
     * 异步提交（在 Controller 的 agentExecutor 上运行）。
     * 成功→ReceiptListener.onSubmissionAccepted；失败/超时→onSubmissionFailed。
     */
    public void submit(SubmitRequest request, String boundConversationId) {
        VoiceResponseToken token = request.token();
        try {
            String conversationId = boundConversationId != null
                    ? boundConversationId : deriveConversationId();
            String agentSessionId = ConversationIds.agentSessionId(conversationId,
                    Actor.DRIVER.name(), "DRIVER");
            ConversationInputChannel channel = boundConversationId == null
                    ? ConversationInputChannel.WAKE : ConversationInputChannel.PTT;
            String idempotencyKey = channel == ConversationInputChannel.PTT
                    ? ConversationIds.pttIdempotencyKey(token.voiceSessionId(), 1)
                    : "wake:" + token.voiceSessionId() + ":" + token.requestNonce();

            ConversationCoordinator.TextAccepted accepted = coordinator.submitText(
                    new ConversationCoordinator.TextCommand(
                            conversationId,
                            request.text(),
                            request.languageTag(),
                            java.util.UUID.randomUUID().toString(),
                            Actor.DRIVER,
                            agentSessionId,
                            "demo-vehicle",
                            new ConversationCoordinator.InputMetadata(channel, InputSource.VOICE,
                                    idempotencyKey, request.asrConfidence(),
                                    request.confidenceAvailable()),
                            receipt -> tokensByTaskId.put(receipt.conversationTaskId(), token)));
            // submitText 是同步事务；成功即 receipt
            Log.i(TAG, "[Bridge] 提交成功 conv=" + conversationId
                    + " replay=" + accepted.replay());
            receiptListener.onSubmissionAccepted(token);
        } catch (Exception failure) {
            Log.e(TAG, "[Bridge] 提交失败 cause=" + failure.getClass().getSimpleName(),
                    failure);
            receiptListener.onSubmissionFailed(token,
                    failure.getClass().getSimpleName());
        }
    }

    /**
     * Coordinator 终态回注入口——由外部（AppContainer 装配的 Listener 适配器）调用。
     * 校验 token 有效性后转发给 TerminalListener。
     */
    public void deliverTerminal(VoiceResponseToken token, AgentOutcome outcome) {
        if (token == null) {
            Log.w(TAG, "[Bridge] 终态回注：token 为 null，丢弃");
            return;
        }
        terminalListener.onConversationTerminal(token, outcome);
    }

    /** PTT partial 只向已绑定的对话透传，绝不落库。 */
    public void publishPartial(String conversationId, String voiceSessionId, String text,
            boolean isFinal) {
        // ConversationServiceStub owns Binder fan-out. The bridge intentionally has no Binder
        // dependency; Controller's UI listener still receives the same transient ASR stream.
    }

    /** 查找或创建 PTT 绑定的 conversation（阶段 B 首版：复用最近线程或新建）。 */
    private String deriveConversationId() {
        var conversations = coordinator.listConversations(
                com.matrix.agent.identity.ActorUsers.USER_DRIVER, false, 1);
        if (!conversations.isEmpty()) {
            return conversations.get(0).conversationId();
        }
        return coordinator.createConversation(
                com.matrix.agent.identity.ActorUsers.USER_DRIVER, "DRIVER", null)
                .conversationId();
    }
}
