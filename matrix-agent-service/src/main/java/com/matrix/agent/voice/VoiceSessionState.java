package com.matrix.agent.voice;

import com.matrix.agent.session.SessionContext;

import com.matrix.agent.voice.port.*;

import android.util.Log;

/**
 * 语音会话状态机。
 *
 * <p>Demo 与量产共享的统一会话状态机。外部组件(ASR / VAD / Agent / TTS)只投递不可变
 * {@link VoiceEvent},由本类唯一负责状态迁移;禁止其它组件直接 start/stop AudioRecord 或 TTS。
 *
 * <p>并发: {@link #transit(VoiceEvent)} 与 {@link #current()} 均 {@code synchronized},参照
 * {@code core.session.SessionContext}。非法事件(当前态不接受)返回原态并记 warning,不抛异常——
 * 参照 Host 任务引擎的 checkpoint 容错,避免一个错事件炸掉整个会话。
 *
 * <p>Demo 约束:{@link State#CONFIRMING} 在 Demo 不可达——遇到
 * {@link VoiceEvent.EventType#NEEDS_CONFIRM} 时 RECOGNIZING / THINKING 都直接回
 * {@link State#IDLE}(Controller 播保守话术),绕过确认状态机。{@link State#CANCELLED} 与
 * {@link State#ERROR_ANNOUNCING} 是过渡态,Controller 清理 / 播报后发
 * {@link VoiceEvent.EventType#RESET} 回 {@link State#IDLE}。
 *
 * @see VoiceEvent
 */
public final class VoiceSessionState {
    private static final String TAG = "MatrixAgent";

    /** 会话状态。 */
    public enum State {
        /** 空闲:释放焦点、停止采音 / TTS、清零短期音频。 */
        IDLE,
        /** 唤醒已接受,校验身份 / zone、播放可选短提示音。 */
        WAKE_ACCEPTED,
        /** 采音中:请求会话期麦克风、启动 VAD / ASR。 */
        LISTENING,
        /** 端点已触发,停止采音,等待 ASR final。 */
        ENDPOINTING,
        /** 校验文本 / 置信度 / 语言 / 敏感命令词。 */
        RECOGNIZING,
        /** 已提交 Agent,等待终态。 */
        THINKING,
        /** Demo 不进入(遇 NEEDS_CONFIRM 直接回 IDLE);保留以与量产状态机一致。 */
        CONFIRMING,
        /** 播报 SpeakableResponse。 */
        SPEAKING,
        /** SPEAKING 中 barge-in 的瞬态,Controller 停 TTS + token.cancel() 后进 LISTENING。 */
        BARGE_IN_LISTENING,
        /** 取消过渡态,Controller 清理后发 RESET 回 IDLE。 */
        CANCELLED,
        /** 错误播报过渡态,Controller 播报后发 RESET 回 IDLE。 */
        ERROR_ANNOUNCING
    }

    private State current = State.IDLE;

    public VoiceSessionState() {
    }

    /** 当前状态。线程安全。 */
    public synchronized State current() {
        return current;
    }

    /**
     * 投递事件,迁移状态。
     *
     * @param event 语音事件,不能为空
     * @return 迁移后的状态;非法事件(当前态不接受)返回原态并记 warning,不抛异常
     */
    public synchronized State transit(VoiceEvent event) {
        if (event == null) throw new IllegalArgumentException("event 不能为空");
        State from = current;
        State to = next(from, event.getType());
        if (to == null) {
            Log.w(TAG, "[Voice] 忽略非法事件: state=" + from + " event=" + event.getType());
            return from;
        }
        current = to;
        if (from != to) {
            Log.i(TAG, "[Voice] state=" + from + " event=" + event.getType() + " -> " + to);
        }
        return to;
    }

    /**
     * 状态迁移表。返回 null 表示当前态不接受该事件(非法,由 transit 记日志后忽略)。
     *
     * <p>未列出的 (state, event) 组合一律返回 null。这保证 {@link State#CONFIRMING} 在 Demo
     * 下从任何态都不可达——单测 {@code confirming_isUnreachableInDemo} 据此全遍历断言。
     */
    private static State next(State from, VoiceEvent.EventType type) {
        switch (from) {
            case IDLE:
                switch (type) {
                    case WAKE: return State.WAKE_ACCEPTED;
                    default: return null;
                }
            case WAKE_ACCEPTED:
                switch (type) {
                    case CAPTURE_STARTED: return State.LISTENING;
                    case CANCEL: return State.CANCELLED;
                    case ERROR: return State.ERROR_ANNOUNCING;
                    default: return null;
                }
            case LISTENING:
                switch (type) {
                    case PARTIAL:
                    case SPEECH: return State.LISTENING;
                    // FINAL 在 LISTENING 不迁移——Controller 缓存为 pendingFinal,
                    // 由 VAD 的 SILENCE_TIMEOUT(端点)驱动 LISTENING→ENDPOINTING,再消费 final。
                    case SILENCE_TIMEOUT: return State.ENDPOINTING;
                    // 未说话是一次正常监听轮结束，先进入可清理的过渡态，再由 RESET 回 IDLE。
                    case NO_SPEECH_TIMEOUT: return State.CANCELLED;
                    case ASR_ERROR:
                    case ERROR: return State.ERROR_ANNOUNCING;
                    case CANCEL: return State.CANCELLED;
                    default: return null;
                }
            case ENDPOINTING:
                switch (type) {
                    case FINAL: return State.RECOGNIZING;
                    case ASR_ERROR:
                    case ERROR: return State.ERROR_ANNOUNCING;
                    case CANCEL: return State.CANCELLED;
                    default: return null;
                }
            case RECOGNIZING:
                switch (type) {
                    case ACCEPTED: return State.THINKING;
                    case REJECTED: return State.LISTENING;
                    // Demo 绕过 CONFIRMING:需确认的能力直接拒绝回 IDLE。
                    case NEEDS_CONFIRM: return State.IDLE;
                    case CANCEL: return State.CANCELLED;
                    case ERROR: return State.ERROR_ANNOUNCING;
                    default: return null;
                }
            case THINKING:
                switch (type) {
                    case TERMINAL: return State.SPEAKING;
                    // Demo 绕过 CONFIRMING:Agent 运行中发现需确认也直接拒绝。
                    case NEEDS_CONFIRM: return State.IDLE;
                    case CANCEL: return State.CANCELLED;
                    case ERROR: return State.ERROR_ANNOUNCING;
                    default: return null;
                }
            case CONFIRMING:
                // Demo 不可达;量产才进入。保守:任何事件都不迁移。
                return null;
            case SPEAKING:
                switch (type) {
                    case TTS_DONE:
                    case FOCUS_LOSS: return State.IDLE;
                    case BARGE_IN: return State.BARGE_IN_LISTENING;
                    case CANCEL: return State.CANCELLED;
                    case ERROR: return State.ERROR_ANNOUNCING;
                    default: return null;
                }
            case BARGE_IN_LISTENING:
                switch (type) {
                    case CAPTURE_STARTED: return State.LISTENING;
                    case CANCEL: return State.CANCELLED;
                    case ERROR: return State.ERROR_ANNOUNCING; // barge-in 中 ASR start 失败,经 error→ERROR_ANNOUNCING→reset→IDLE
                    default: return null;
                }
            case CANCELLED:
                switch (type) {
                    case RESET: return State.IDLE;
                    default: return null;
                }
            case ERROR_ANNOUNCING:
                switch (type) {
                    case RESET: return State.IDLE;
                    default: return null;
                }
            default:
                return null;
        }
    }
}
