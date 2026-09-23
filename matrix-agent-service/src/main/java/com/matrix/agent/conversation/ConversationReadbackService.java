package com.matrix.agent.conversation;

import android.util.Log;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.port.AudioFocusPort;
import com.matrix.agent.voice.port.ManagedTtsPort;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 助手回复朗读（评估 v1.0 §4.8 / 阶段 2）：从 Store 重读已持久化、经投影允许朗读的
 * 最终文本，经既有 TTS/焦点端口治理播报。
 *
 * <p><b>治理复用</b>：TtsPort 与 AudioFocusPort 与语音运行时同一契约——本服务持有
 * 自己的端口实例但绝不新增第二套焦点规则（同一 AudioFocusPort 仲裁类，独占式请求，
 * 播报结束即释放）。活跃语音会话（录音/识别/唤醒交接/播报中）由调用方先行拒绝
 * （stub 检查 VoiceRuntimeHolder），本服务再做会话内互斥：新朗读停止旧朗读。</p>
 *
 * <p><b>幂等/迟到防御</b>：utteranceId 单调递增，迟到 onDone/onError 只按当前代次
 * 收敛焦点；终态后清理 current 使后续 stop 成为 no-op。</p>
 */
public final class ConversationReadbackService implements AutoCloseable {

    private static final String TAG = "MatrixAgent";

    /** 朗读文本上限（与助手投影一致的展示预算）。 */
    private static final int READBACK_MAX_CHARS = 2000;

    private final ConversationStore store;
    private final Supplier<ManagedTtsPort> outputFactory;
    private final AudioFocusPort focus;
    private final BooleanSupplier voiceSessionActive;
    /** Serializes route replacement with speaking so an old asynchronous callback is harmless. */
    private final Object outputLock = new Object();
    private ManagedTtsPort output;
    private boolean closed;

    private final AtomicLong utteranceSequence = new AtomicLong();
    private final AtomicReference<String> currentUtterance = new AtomicReference<>();

    public ConversationReadbackService(ConversationStore store,
            Supplier<ManagedTtsPort> outputFactory,
            AudioFocusPort focus, BooleanSupplier voiceSessionActive) {
        this.store = Objects.requireNonNull(store, "store");
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory");
        this.focus = Objects.requireNonNull(focus, "focus");
        this.voiceSessionActive = Objects.requireNonNull(voiceSessionActive,
                "voiceSessionActive");
        synchronized (outputLock) {
            output = createOutput();
        }
        this.focus.setListener(() -> {
            // 焦点丢失（来电/导航/语音会话抢焦点）——立即停读并收敛。
            // 此处不可 release：焦点已经被系统收回。
            stopOutputOnly();
            currentUtterance.set(null);
        });
    }

    private ManagedTtsPort createOutput() {
        ManagedTtsPort next = Objects.requireNonNull(outputFactory.get(), "outputFactory result");
        next.setListener(new com.matrix.agent.voice.port.TtsPort.Listener() {
            @Override public void onDone(String utteranceId) {
                finishIfCurrent(utteranceId);
            }

            @Override public void onError(String utteranceId, String code) {
                Log.w(TAG, "[Readback] 播报失败 utterance=" + utteranceId
                        + " code=" + code);
                finishIfCurrent(utteranceId);
            }
        });
        return next;
    }

    /** 稳定错误码（stub 映射 MatrixErrorCode）。 */
    public static final String ERR_NOT_FOUND = "NOT_FOUND";
    public static final String ERR_NOT_READABLE = "NOT_READABLE";
    public static final String ERR_VOICE_ACTIVE = "VOICE_ACTIVE";

    /**
     * 朗读一条助手最终回复。可空错误码：null = 已开始播报；NOT_FOUND=消息不存在/越权；
     * NOT_READABLE=非助手行或非 COMPLETED 终态；VOICE_ACTIVE=活跃语音会话（禁用）。
     */
    public String speak(String conversationId, String assistantMessageId, String ownerUserId) {
        synchronized (outputLock) {
            if (closed) return ERR_VOICE_ACTIVE;
        }
        if (voiceSessionActive.getAsBoolean()) {
            return ERR_VOICE_ACTIVE;
        }
        ConversationStore.MessageRow message = store.findMessage(assistantMessageId);
        ConversationStore.ConversationRow conversation = store.findConversation(conversationId);
        if (message == null || conversation == null
                || !message.conversationId().equals(conversationId)
                || !conversation.ownerUserId().equals(ownerUserId)) {
            return ERR_NOT_FOUND; // 统一不可定位，不泄漏存在性
        }
        if (message.roleWire() != ConversationMessage.ROLE_ASSISTANT
                || message.statusWire()
                        != ConversationDomain.PersistedMessageStatus.COMPLETED.wire()
                || message.text().isBlank()) {
            return ERR_NOT_READABLE;
        }
        // 新朗读停止旧朗读（会话内互斥；焦点在同一实例上重请求）
        stop();
        if (!focus.request()) {
            Log.w(TAG, "[Readback] 焦点被拒，朗读未开始");
            return ERR_VOICE_ACTIVE;
        }
        String utteranceId = "readback-" + utteranceSequence.incrementAndGet();
        currentUtterance.set(utteranceId);
        String text = message.text().length() > READBACK_MAX_CHARS
                ? message.text().substring(0, READBACK_MAX_CHARS) : message.text();
        synchronized (outputLock) {
            if (closed || output == null) {
                currentUtterance.compareAndSet(utteranceId, null);
                focus.release();
                return ERR_VOICE_ACTIVE;
            }
            output.speak(new SpeakableResponse(text,
                    message.languageTag() == null ? Locale.getDefault().toLanguageTag()
                            : message.languageTag(),
                    TaskState.SUCCEEDED), utteranceId);
        }
        Log.i(TAG, "[Readback] 开始朗读 msg=" + assistantMessageId
                + " utterance=" + utteranceId + " chars=" + text.length());
        return null;
    }

    /** 停止当前朗读并释放焦点（幂等）。 */
    public void stop() {
        stopOutputOnly();
        finishIfCurrent(currentUtterance.get());
    }

    /** Re-resolve credentials only at an idle boundary chosen by the Voice service. */
    public void refreshOutputRoute() {
        String interrupted;
        ManagedTtsPort previous;
        synchronized (outputLock) {
            if (closed) return;
            interrupted = currentUtterance.getAndSet(null);
            previous = output;
            output = createOutput();
        }
        // A configured-route change is explicit user intent; do not continue a response through
        // a now-obsolete engine.  Shutdown occurs after swap so late callbacks cannot touch a
        // future utterance (IDs are monotonic).
        try {
            previous.stop();
            previous.shutdown();
        } finally {
            if (interrupted != null) focus.release();
        }
    }

    @Override public void close() {
        ManagedTtsPort previous;
        synchronized (outputLock) {
            if (closed) return;
            closed = true;
            previous = output;
            output = null;
            currentUtterance.set(null);
        }
        if (previous != null) {
            previous.stop();
            previous.shutdown();
        }
        focus.release();
    }

    private void stopOutputOnly() {
        synchronized (outputLock) {
            if (output != null) output.stop();
        }
    }

    private void finishIfCurrent(String utteranceId) {
        String current = currentUtterance.get();
        if (current == null || !current.equals(utteranceId)) {
            return; // 迟到回调按代次丢弃
        }
        currentUtterance.compareAndSet(current, null);
        focus.release();
    }
}
