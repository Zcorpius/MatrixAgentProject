package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import android.util.Log;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.voice.port.AsrPort;
import com.matrix.agent.voice.AgentRunner;
import com.matrix.agent.voice.port.AudioFocusPort;
import com.matrix.agent.voice.FinalTranscript;
import com.matrix.agent.voice.NoopVoiceMetrics;
import com.matrix.agent.voice.ResponsePresenter;
import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.TranscriptValidator;
import com.matrix.agent.voice.port.TtsPort;
import com.matrix.agent.voice.VadEvent;
import com.matrix.agent.voice.port.VadPort;
import com.matrix.agent.voice.VoiceAgentRequest;
import com.matrix.agent.voice.VoiceEvent;
import com.matrix.agent.voice.VoiceMetricsPort;
import com.matrix.agent.voice.VoicePolicyConfig;
import com.matrix.agent.voice.VoiceSessionListener;
import com.matrix.agent.voice.VoiceSessionState;
import com.matrix.agent.voice.port.WakeWordPort;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 语音会话控制器。
 *
 * <p>maxSpeech 调 asr.finish 不丢识别;failAndCleanup 统一收敛(先 error→ERROR_ANNOUNCING→reset);
 * onWake 检查 IDLE(防 manualWake 破坏会话);focus loss 后重启 KWS(不永久耳聋);
 * ready timeout 绑 generation(fire 校验 SPEAKING+pendingSpeak,旧轮丢弃);utteranceId 贯通(丢弃旧播报迟到回调)。
 */
public final class VoiceSessionController {
    private static final String TAG = "MatrixAgent";

    private final VoiceSessionState state = new VoiceSessionState();
    private final WakeWordPort wakePort;
    private final AsrPort asrPort;
    private final TtsPort ttsPort;
    private final VadPort vadPort;
    private final AudioFocusPort focusPort;
    private final AgentRunner runner;
    private final ResponsePresenter presenter;
    private final VoicePolicyConfig policy;
    private final Executor stateExecutor;
    private final Executor agentExecutor;
    private final ScheduledExecutorService timeoutScheduler;
    private final TranscriptValidator validator = new TranscriptValidator();
    private final VoiceMetricsPort metrics;

    private final WakeWordPort.Listener wakeListener;
    private final AsrPort.Listener asrListener;
    private final TtsPort.Listener ttsListener;
    private final VadPort.Listener vadListener;
    private final AudioFocusPort.Listener focusListener;

    private volatile CancellationToken currentToken;
    private volatile String currentLang = "zh-CN";
    private volatile VoiceSessionListener uiListener;
    private volatile boolean closed;

    private volatile long generation;
    /** 当前 ASR/VAD 会话的 sessionId(= engine epoch),回调校验防旧会话污染。 */
    private volatile long expectedAsrSid;
    /** 当前 KWS recognizer 代次(= start() 返回 epoch),onWake/onError 校验防旧 recognizer
     * 迟到唤醒污染重建后的新会话(采音错误 stop+start 窗口)。 */
    private volatile long expectedWakeEpoch = Long.MIN_VALUE;
    private volatile int repromptCount;
    /** 唤醒重建已重试次数(onWake 成功重置,onWakeError 递增,上限 3)。 */
    private volatile int wakeRetryCount;
    /** 唤醒重建延迟任务句柄;onWake/cleanup/shutdown 取消,防旧重试执行。 */
    private volatile ScheduledFuture<?> wakeRetryFuture;
    private volatile FinalTranscript pendingFinal;
    private volatile ScheduledFuture<?> speechStartFuture;
    private volatile ScheduledFuture<?> maxSpeechFuture;
    private volatile ScheduledFuture<?> finalWaitFuture;

    private volatile boolean ttsReady;
    /** TTS 永久初始化失败(onInitError);后续 onTerminal 直接播报失败,不再排 ready 超时重复等。 */
    private volatile boolean ttsInitFailed;
    private volatile SpeakableResponse pendingSpeak;
    private volatile ScheduledFuture<?> ttsReadyFuture;
    private volatile ScheduledFuture<?> speakTimeoutFuture;
    /** 当前期望的 utteranceId,用于丢弃旧播报的迟到 onDone/onError。 */
    private volatile String expectedUtteranceId;
    /** TTS utterance id 序列(Controller 生成,传 TtsPort.speak,防端口内同步回调因 ID 未设被丢)。 */
    private final java.util.concurrent.atomic.AtomicInteger utteranceSeq = new java.util.concurrent.atomic.AtomicInteger();

    /** TTS 播报 watchdog 最小时长:短文本(如"已完成。"按字数仅 ~800ms)防冷启动误杀。 */
    private static final long TTS_MIN_SPEAK_MS = 3000L;
    /** TTS 启动余量:补偿 speak 调用到首帧发声的冷启动延迟。 */
    private static final long TTS_START_PADDING_MS = 2000L;

    private long lastStateNanos = System.nanoTime();
    private volatile long agentStartNanos;

    public VoiceSessionController(WakeWordPort wakePort, AsrPort asrPort, TtsPort ttsPort,
            VadPort vadPort, AudioFocusPort focusPort, AgentRunner runner, ResponsePresenter presenter,
            VoicePolicyConfig policy, Executor stateExecutor, Executor agentExecutor,
            ScheduledExecutorService timeoutScheduler, VoiceMetricsPort metrics) {
        if (wakePort == null) throw new IllegalArgumentException("wakePort 不能为空");
        if (asrPort == null) throw new IllegalArgumentException("asrPort 不能为空");
        if (ttsPort == null) throw new IllegalArgumentException("ttsPort 不能为空");
        if (vadPort == null) throw new IllegalArgumentException("vadPort 不能为空");
        if (focusPort == null) throw new IllegalArgumentException("focusPort 不能为空");
        if (runner == null) throw new IllegalArgumentException("runner 不能为空");
        if (presenter == null) throw new IllegalArgumentException("presenter 不能为空");
        if (policy == null) throw new IllegalArgumentException("policy 不能为空");
        if (stateExecutor == null) throw new IllegalArgumentException("stateExecutor 不能为空");
        if (agentExecutor == null) throw new IllegalArgumentException("agentExecutor 不能为空");
        if (timeoutScheduler == null) throw new IllegalArgumentException("timeoutScheduler 不能为空");
        this.wakePort = wakePort;
        this.asrPort = asrPort;
        this.ttsPort = ttsPort;
        this.vadPort = vadPort;
        this.focusPort = focusPort;
        this.runner = runner;
        this.presenter = presenter;
        this.policy = policy;
        this.stateExecutor = stateExecutor;
        this.agentExecutor = agentExecutor;
        this.timeoutScheduler = timeoutScheduler;
        this.metrics = metrics != null ? metrics : NoopVoiceMetrics.INSTANCE;

        this.wakeListener = new WakeWordPort.Listener() {
            @Override public void onWake(long epoch) { VoiceSessionController.this.onWake(epoch); }
            @Override public void onError(String code, long epoch) { VoiceSessionController.this.onWakeError(code, epoch); }
        };
        this.asrListener = new AsrPort.Listener() {
            @Override public void onPartial(String text, long sid) { VoiceSessionController.this.onPartial(text, sid); }
            @Override public void onFinal(FinalTranscript transcript, long sid) { VoiceSessionController.this.onFinal(transcript, sid); }
            @Override public void onError(String code, long sid) { VoiceSessionController.this.onAsrError(code, sid); }
        };
        this.ttsListener = new TtsPort.Listener() {
            @Override public void onDone(String utteranceId) { VoiceSessionController.this.onTtsDone(utteranceId); }
            @Override public void onError(String utteranceId, String code) { VoiceSessionController.this.onTtsError(utteranceId, code); }
            @Override public void onReady() { VoiceSessionController.this.onTtsReady(); }
            @Override public void onInitError(String code) { VoiceSessionController.this.onTtsInitError(code); }
        };
        this.vadListener = (event, sid) -> runInState(() -> {
            if (event == VadEvent.ENDPOINT) onEndpoint(sid);
        });
        this.focusListener = () -> runInState(VoiceSessionController.this::onFocusLoss);
    }

    public void setUiListener(VoiceSessionListener listener) { this.uiListener = listener; }
    public VoiceSessionState.State currentState() { return state.current(); }
    public int repromptCount() { return repromptCount; }

    public void start() {
        wakePort.setListener(wakeListener);
        asrPort.setListener(asrListener);
        vadPort.setListener(vadListener);
        ttsPort.setListener(ttsListener);
        focusPort.setListener(focusListener);
        runInState(() -> {
            startWakeKws();
            // 不在启动时排 TTS ready 超时:冷启动立即唤醒时,3s 超时会在 LISTENING/THINKING 误杀正常识别。
            // 只有 onTerminal 产生 pendingSpeak(确实要播报)时才排 ready 超时(P1-3)。
            Log.i(TAG, "[Voice] Controller 启动,等待唤醒");
        });
    }

    public void shutdown(Runnable afterStopped) {
        closed = true;
        cancelAllTimeouts();
        cancelFuture(ttsReadyFuture);
        ttsReadyFuture = null;
        cancelFuture(wakeRetryFuture);
        wakeRetryFuture = null;
        expectedUtteranceId = null;
        expectedAsrSid = Long.MIN_VALUE; // 作废,旧采音线程回调丢弃
        pendingFinal = null;
        pendingSpeak = null;
        stateExecutor.execute(() -> {
            try {
                if (currentToken != null) currentToken.cancel();
                asrPort.stop();
                wakePort.stop();
                ttsPort.stop();
                focusPort.release();
                uiListener = null;
            } finally {
                if (afterStopped != null) afterStopped.run();
            }
        });
    }

    private void runInState(Runnable task) {
        if (closed) return;
        try {
            stateExecutor.execute(() -> { if (!closed) task.run(); });
        } catch (RejectedExecutionException e) {
            if (!closed) throw e;
            Log.d(TAG, "[Voice] 忽略关闭后的 state 回调");
        }
    }

    // ---- 端口回调 ----

    /** 启动 ASR 并设 expectedAsrSid;失败 failAndCleanup 返回 false(结构化结果,不依赖 onError 时序)。 */
    private boolean startAsr() {
        AsrPort.AsrStartResult r = asrPort.start();
        expectedAsrSid = r.sessionId;
        if (!r.success()) {
            metrics.onError(r.errorCode, "ASR");
            failAndCleanup(r.errorCode);
            return false;
        }
        return true;
    }

    /**
     * 启动/重新布防 KWS。成功记录 recognizer 代次并清零 wakeRetryCount(重建成功重获 3 次机会,
     * 不等用户说唤醒词);失败走 {@link #handleWakeError} 有限退避重建。返回值驱动,
     * 不依赖 listener.onError 同步时序(对称 {@link #startAsr})。
     */
    private void startWakeKws() {
        WakeWordPort.WakeStartResult r = wakePort.start();
        if (r.success()) {
            expectedWakeEpoch = r.epoch;
            wakeRetryCount = 0;
        } else {
            handleWakeError(r.errorCode);
        }
    }

    private void onWake(long epoch) {
        runInState(() -> {
            // P2:代次校验——native 结果锁外派发期间可能 stop+start 重建,旧 recognizer 的迟到唤醒丢弃
            if (epoch != expectedWakeEpoch) {
                Log.i(TAG, "[Voice] 旧代次唤醒(epoch=" + epoch + ",当前 " + expectedWakeEpoch + "),丢弃");
                return;
            }
            acceptWake("vosk");
        });
    }

    /** 接受唤醒进入新会话(KWS 代次校验通过或用户手动唤醒)。仅 IDLE 接受。 */
    /** 接受唤醒进入新会话;source 贯通 metrics(vosk/manual/SYSTEM_VIS)。仅 IDLE 接受。 */
    private void acceptWake(String source) {
        // 仅 IDLE 接受唤醒;重复 KWS 或 THINKING/SPEAKING 时 manualWake 不破坏当前会话。
        if (state.current() != VoiceSessionState.State.IDLE) {
            Log.w(TAG, "[Voice] onWake 非 IDLE(state=" + state.current() + "),忽略");
            return;
        }
        if (transit(VoiceEvent.wake()) != VoiceSessionState.State.WAKE_ACCEPTED) return;
        generation++;
        repromptCount = 0;
        wakeRetryCount = 0; // 唤醒成功,重置重建计数
        cancelFuture(wakeRetryFuture); wakeRetryFuture = null; // 取消旧重试(防迟到重试中断新会话)
        pendingFinal = null;
        pendingSpeak = null;
        cancelAllTimeouts();
        metrics.onWake(source);
        Log.i(TAG, "[Voice] wake accepted source=" + source
                + ", waiting_for_command timeoutMs=" + policy.speechStartMs());
        wakePort.stop();
        if (!startAsr()) return;
        vadPort.reset();
        transit(VoiceEvent.captureStarted());
        startListeningTimeouts();
    }

    private void onPartial(String text, long sid) {
        runInState(() -> {
            if (sid != expectedAsrSid) return; // 旧会话回调丢弃
            if (uiListener != null) uiListener.onPartial(text);
            if (text != null && !text.trim().isEmpty()
                    && state.current() == VoiceSessionState.State.LISTENING) {
                cancelFuture(speechStartFuture);
                speechStartFuture = null;
            }
        });
    }

    private void onFinal(FinalTranscript transcript, long sid) {
        runInState(() -> {
            if (sid != expectedAsrSid) return; // 旧会话回调丢弃
            VoiceSessionState.State s = state.current();
            if (s == VoiceSessionState.State.LISTENING) {
                pendingFinal = transcript;
            } else if (s == VoiceSessionState.State.ENDPOINTING) {
                consumeFinal(transcript);
            } else {
                Log.w(TAG, "[Voice] onFinal 在非 LISTENING/ENDPOINTING 态(" + s + "),丢弃");
            }
        });
    }

    private void onEndpoint(long sid) {
        if (sid != expectedAsrSid) return; // 旧会话回调丢弃
        VoiceSessionState.State prev = state.current();
        transit(VoiceEvent.silenceTimeout());
        if (prev != VoiceSessionState.State.LISTENING) return;
        cancelSpeechStartAndMax();
        if (pendingFinal != null) {
            FinalTranscript t = pendingFinal;
            pendingFinal = null;
            consumeFinal(t);
        } else {
            cancelFinalWait();
            finalWaitFuture = scheduleAsrTimeout(policy.finalWaitMs(), this::handleFinalWaitTimeout);
        }
    }

    /** P3:先作废 expectedAsrSid 再 stop()。端口契约允许 stop() 内同步/并发回调——先作废,
     * 旧 sid 的 onError 才不会命中当前会话(复述时 startAsr() 会覆盖为新 sid)。 */
    private void invalidateAndStopAsr() {
        expectedAsrSid = Long.MIN_VALUE;
        asrPort.stop();
    }

    private void consumeFinal(FinalTranscript transcript) {
        VoiceSessionState.State s = transit(
                VoiceEvent.finalTranscript(transcript.text(), transcript.confidence()));
        if (s != VoiceSessionState.State.RECOGNIZING) {
            Log.w(TAG, "[Voice] consumeFinal 未到 RECOGNIZING, state=" + s);
            return;
        }
        cancelFinalWait();
        invalidateAndStopAsr(); // P2/P3: 正常成功路径也作废(先作废再 stop,旧 sid 的迟到/同步 onAsrError 不误杀 THINKING/SPEAKING)
        TranscriptValidator.Verdict v = validator.validate(transcript, policy, repromptCount);
        if (v.accepted()) {
            VoiceSessionListener listener = uiListener;
            if (listener != null) listener.onFinal(transcript.text());
            transit(VoiceEvent.accepted());
            currentLang = transcript.languageTag();
            CancellationToken token = new CancellationToken();
            currentToken = token;
            agentStartNanos = System.nanoTime();
            final long gen = generation; // 提交时捕获,回调校验防跨会话污染
            VoiceAgentRequest vreq = new VoiceAgentRequest(transcript, token);
            try {
                agentExecutor.execute(() -> runAgent(vreq, gen));
            } catch (RejectedExecutionException e) {
                if (closed) return;
                Log.w(TAG, "[Voice] Agent executor 已关闭");
                handleRunnerError(gen);
            }
        } else {
            repromptCount++;
            Log.i(TAG, "[Voice] 转写校验拒绝: " + v.reason() + ", repromptCount=" + repromptCount);
            if (repromptCount <= policy.maxReprompt()) {
                transit(VoiceEvent.rejected());
                if (!startAsr()) return;
                startListeningTimeouts();
            } else {
                failAndCleanup("REPROMPT_EXCEEDED");
            }
        }
    }

    private void runAgent(VoiceAgentRequest vreq, long gen) {
        AgentOutcome outcome;
        try {
            outcome = runner.run(vreq);
        } catch (Exception e) {
            Log.e(TAG, "[Voice] AgentRunner 异常: " + e.getMessage(), e);
            runInState(() -> handleRunnerError(gen));
            return;
        }
        final AgentOutcome o = outcome;
        runInState(() -> onTerminal(o, gen));
    }

    private void onTerminal(AgentOutcome outcome, long gen) {
        if (state.current() != VoiceSessionState.State.THINKING || generation != gen) {
            Log.i(TAG, "[Voice] onTerminal 时 state=" + state.current() + " gen=" + gen
                    + "(当前 " + generation + "),忽略迟到 outcome");
            return;
        }
        metrics.onAgentLatency(System.nanoTime() - agentStartNanos);
        SpeakableResponse resp = presenter.present(outcome, currentLang);
        transit(VoiceEvent.terminal());
        // P2-2: 焦点在 speakNow 内抢(TTS ready 后真正 speak 前),不在 TTS 未就绪时提前压低音乐
        if (ttsReady) {
            speakNow(resp);
        } else if (ttsInitFailed) {
            // TTS 永久初始化失败:播报直接失败收敛(不排 ready 超时,不重复等 3s)
            Log.w(TAG, "[Voice] TTS 永久未就绪,播报失败");
            failAndCleanup("TTS_INIT_FAILED");
        } else {
            pendingSpeak = resp; // INITIALIZING:等 onReady flush
            scheduleTtsReadyTimeout(); // 只有要播报时才排 ready 超时
        }
    }

    private void speakNow(SpeakableResponse resp) {
        // P2-2: 紧贴 speak 前抢焦点(TTS ready 后);避免 TTS 未就绪/初始化时提前压低音乐
        if (!focusPort.request()) {
            Log.w(TAG, "[Voice] 焦点被拒绝,不播报");
            failAndCleanup("FOCUS_DENIED");
            return;
        }
        // P3-2: request() 可能重入触发 focus loss(已回 IDLE);确认仍 SPEAKING 才继续,否则释放焦点返回
        if (state.current() != VoiceSessionState.State.SPEAKING) {
            Log.w(TAG, "[Voice] request 后状态已变(focus loss 重入),不播报");
            focusPort.release();
            return;
        }
        // Controller 先生成 utteranceId 并在 speak 前设期望 id:防 speak() 内同步 onError/onDone
        // 在 expectedUtteranceId 设置前到达被丢(单线程 executor 掩盖该竞态,换 direct/其他 executor 会暴露)
        String utteranceId = "voice_tts_" + utteranceSeq.incrementAndGet();
        expectedUtteranceId = utteranceId;
        ttsPort.speak(resp, utteranceId);
        // P3: speak() 内同步 onError/onDone 可能已清 expectedUtteranceId + 回 IDLE;
        // 仅当仍 SPEAKING 且 id 匹配(未被同步回调清)才排 watchdog,避免挂无效 timeout
        if (state.current() != VoiceSessionState.State.SPEAKING
                || !utteranceId.equals(expectedUtteranceId)) {
            return;
        }
        int charCount = resp.getCharCount();
        if (charCount > 0) {
            // 短文本(如"已完成。"按字数仅 ~800ms)在冷启动/慢设备下来不及播完即超时误杀:
            // 最小值保底 + 启动余量补偿 speak→首帧发声延迟。注:更精确是从 onStart(发声)起计,
            // 但需 onStart 回调上浮 + 其不到达的兜底 watchdog(接口扩展);watchdog 本是粗粒度兜底,
            // 启动余量是标准工程补偿,此处取低成本方案。
            long estimated = Math.max(
                    Math.min((long) charCount * policy.ttsPerCharMs(), policy.ttsMaxSpeakMs()),
                    TTS_MIN_SPEAK_MS);
            cancelFuture(speakTimeoutFuture);
            speakTimeoutFuture = scheduleSpeakTimeout(estimated + TTS_START_PADDING_MS, this::handleSpeakTimeout);
        }
    }

    // ---- TTS 生命周期 ----

    /** 清理待播报状态:丢弃 pendingSpeak + 取消 ready 超时。focus loss/cleanup/barge-in/init error
     * 调用,防迟到的 onTtsReady / ready timeout 在错误态播报或跨会话误杀。 */
    private void clearPendingTts() {
        pendingSpeak = null;
        cancelFuture(ttsReadyFuture);
        ttsReadyFuture = null;
    }

    private void onTtsReady() {
        runInState(() -> {
            ttsReady = true;
            // 仅在 SPEAKING 等播报态且有 pending 才 flush;否则(会话已变)丢弃,不在错误态播报
            if (pendingSpeak != null && state.current() == VoiceSessionState.State.SPEAKING) {
                SpeakableResponse pending = pendingSpeak;
                clearPendingTts();
                speakNow(pending);
            } else {
                clearPendingTts();
            }
        });
    }

    private void onTtsInitError(String code) {
        runInState(() -> {
            if (closed) return;
            Log.w(TAG, "[Voice] TTS 初始化失败: " + code);
            ttsInitFailed = true; // 永久失败:后续 onTerminal 直接播报失败
            clearPendingTts();
            metrics.onError(code, "TTS_INIT");
            // 仅 SPEAKING(正在等播报)收敛;LISTENING/THINKING 的 Agent 不被异步 TTS init 打断(P2-A)
            if (state.current() == VoiceSessionState.State.SPEAKING) {
                failAndCleanup(code);
            }
        });
    }

    /** ready timeout 绑 generation:旧会话排的超时在新会话 fire 时丢弃(对齐 scheduleAsrTimeout)。 */
    private void scheduleTtsReadyTimeout() {
        cancelFuture(ttsReadyFuture);
        final long gen = generation;
        try {
            ttsReadyFuture = timeoutScheduler.schedule(() -> runInState(() -> {
                if (closed || generation != gen) return;
                handleTtsReadyTimeout();
            }), policy.ttsReadyMs(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!closed) Log.w(TAG, "[Voice] ttsReady scheduler 拒绝");
        }
    }

    private void handleTtsReadyTimeout() {
        if (ttsReady) return;
        // 仅仍在 SPEAKING 等播报(pendingSpeak 非空)才收敛;否则(会话已变/无 pending)丢弃,不误杀新会话
        if (state.current() != VoiceSessionState.State.SPEAKING || pendingSpeak == null) {
            Log.i(TAG, "[Voice] TTS ready 超时但非播报等待态,丢弃");
            clearPendingTts();
            return;
        }
        Log.w(TAG, "[Voice] TTS 就绪超时,回 IDLE");
        metrics.onTimeout("TTS_READY", "TTS");
        failAndCleanup("TTS_READY_TIMEOUT");
    }

    private void handleSpeakTimeout() {
        if (state.current() != VoiceSessionState.State.SPEAKING) return;
        Log.w(TAG, "[Voice] TTS 播报超时,停止并回 IDLE");
        metrics.onTimeout("TTS_SPEAK", "TTS");
        failAndCleanup("TTS_SPEAK_TIMEOUT"); // 统一收敛
    }

    private void onTtsDone(String utteranceId) {
        runInState(() -> {
            if (!utteranceMatches(utteranceId)) return; // 丢弃旧/迟到
            cancelFuture(speakTimeoutFuture);
            speakTimeoutFuture = null;
            expectedUtteranceId = null;
            focusPort.release();
            transit(VoiceEvent.ttsDone());
            startWakeKws();
        });
    }

    private void onTtsError(String utteranceId, String code) {
        runInState(() -> {
            if (!utteranceMatches(utteranceId)) return; // 丢弃旧/迟到
            Log.w(TAG, "[Voice] TTS 错误: " + code);
            metrics.onError(code, "TTS");
            failAndCleanup(code);
        });
    }

    /** 校验 utteranceId 是否当前期望(stop/cleanup/新 speak 会使旧 id 失效)。 */
    private boolean utteranceMatches(String utteranceId) {
        return utteranceId != null && utteranceId.equals(expectedUtteranceId);
    }

    private void onFocusLoss() {
        if (state.current() != VoiceSessionState.State.SPEAKING) return;
        Log.i(TAG, "[Voice] 焦点丢失,停止播报回 IDLE,重新布防唤醒");
        cancelFuture(speakTimeoutFuture);
        speakTimeoutFuture = null;
        expectedUtteranceId = null;
        clearPendingTts(); // 清 pendingSpeak + ready 超时,防迟到 onTtsReady 在 IDLE 无焦点播报
        ttsPort.stop();
        transit(VoiceEvent.focusLoss()); // SPEAKING→IDLE
        focusPort.release();
        startWakeKws(); // 重新布防 KWS(不恢复 TTS,但唤醒必须可用,否则一次抢焦点永久耳聋)
    }

    private void onAsrError(String code, long sid) {
        runInState(() -> {
            // 统一校验 sid==expectedAsrSid;start 失败时 asrPort.start() 返回 -1 且 onWake 已把 expectedAsrSid 设为 -1,故仍匹配处理。
            if (sid != expectedAsrSid) return;
            Log.w(TAG, "[Voice] ASR 错误: " + code);
            metrics.onError(code, "ASR");
            failAndCleanup(code);
        });
    }

    private void onWakeError(String code, long epoch) {
        runInState(() -> {
            // P2:代次校验——旧 recognizer 的迟到错误不触发退避重建(对称 onWake)
            if (epoch != expectedWakeEpoch) return;
            handleWakeError(code);
        });
    }

    private void handleWakeError(String code) {
        Log.w(TAG, "[Voice] 唤醒错误: " + code + ",尝试有限重建");
        metrics.onError(code, "WAKE");
        scheduleWakeRetry();
    }

    /** 唤醒 recognizer 失败后有限退避重建(3 次),仍失败 → 不可用态。onWake 成功时重置计数。 */
    private void scheduleWakeRetry() {
        if (wakeRetryCount >= 3) {
            Log.e(TAG, "[Voice] 唤醒重建耗尽,进入不可用态(保持 KWS 关闭)");
            wakeRetryCount = 0; // 允许将来用户手动重试时重新计数
            metrics.onError("WAKE_UNAVAILABLE", "WAKE");
            transit(VoiceEvent.error("WAKE_UNAVAILABLE"));
            cleanupAndIdle(false); // 不重启 KWS(重启还会失败),唤醒保持关闭直到用户手动重试
            VoiceSessionListener l = uiListener;
            if (l != null) l.onError("WAKE_UNAVAILABLE"); // 上报 UI:语音唤醒不可用(手动唤醒仍可用)
            return;
        }
        wakeRetryCount++;
        final int attempt = wakeRetryCount;
        final long gen = generation;
        try {
            wakeRetryFuture = timeoutScheduler.schedule(() -> runInState(() -> {
                if (closed || generation != gen) return; // 旧 generation(用户已新会话),丢弃
                if (state.current() != VoiceSessionState.State.IDLE) return; // 非空闲,不重试
                Log.i(TAG, "[Voice] 唤醒重建(第 " + attempt + " 次)");
                startWakeKws(); // 成功清零 wakeRetryCount(重获 3 次机会);失败→onWakeError→scheduleWakeRetry
            }), 500L, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!closed) Log.w(TAG, "[Voice] 唤醒重试 scheduler 拒绝");
        }
    }

    private void handleRunnerError(long gen) {
        if (generation != gen) return; // 旧 Agent 的错误不污染新会话
        metrics.onError("AGENT_RUNNER_FAILED", "AGENT");
        failAndCleanup("AGENT_RUNNER_FAILED");
    }

    /**
     * 统一失败收敛。先 transit(error)→ERROR_ANNOUNCING(活动态都接受 error),
     * 再 cleanupAndIdle(reset→IDLE)。从 IDLE/CANCELLED 调用时 error 不迁移,reset 直接回 IDLE。
     */
    private void failAndCleanup(String code) {
        transit(VoiceEvent.error(code)); // 活动→ERROR_ANNOUNCING;IDLE/CANCELLED→原态
        cleanupAndIdle();
    }

    private void cleanupAndIdle() { cleanupAndIdle(true); }

    /**
     * 统一清理回 IDLE。restartWake=true 时重启 KWS(常规失败后恢复唤醒);
     * WAKE_UNAVAILABLE 用 false(重启还会失败,保持唤醒关闭)。
     */
    private void cleanupAndIdle(boolean restartWake) {
        cancelAllTimeouts();
        cancelFuture(wakeRetryFuture);
        wakeRetryFuture = null;
        pendingFinal = null;
        clearPendingTts(); // 替换 pendingSpeak=null + 取消 ttsReadyFuture
        expectedUtteranceId = null;
        if (currentToken != null) currentToken.cancel();
        invalidateAndStopAsr(); // P3-1: 作废,旧采音线程已取出的 partial/onError 回调全部丢弃(先作废再 stop)
        ttsPort.stop();
        focusPort.release();
        transit(VoiceEvent.reset());
        if (restartWake) startWakeKws();
    }

    // ---- 会话超时 watchdog ----

    private void startListeningTimeouts() {
        cancelSpeechStartAndMax();
        speechStartFuture = scheduleAsrTimeout(policy.speechStartMs(), this::handleSpeechStartTimeout);
        maxSpeechFuture = scheduleAsrTimeout(policy.maxSpeechMs(), this::handleMaxSpeechTimeout);
    }

    /** ASR 阶段 timeout(speechStart/maxSpeech/finalWait):捕获 expectedAsrSid,fire 校验防旧 ASR 轮(cancel 来不及)误杀新轮。 */
    private ScheduledFuture<?> scheduleAsrTimeout(long delayMs, Runnable onFireStateTask) {
        if (delayMs <= 0) return null;
        final long gen = generation;
        final long sid = expectedAsrSid;
        try {
            return timeoutScheduler.schedule(() -> runInState(() -> {
                if (closed || generation != gen || expectedAsrSid != sid) return; // 旧 ASR 轮丢弃
                onFireStateTask.run();
            }), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!closed) Log.w(TAG, "[Voice] timeout scheduler 拒绝");
            return null;
        }
    }

    /** TTS 播报 timeout:捕获 expectedUtteranceId,fire 校验防旧 utterance 误杀新播报。 */
    private ScheduledFuture<?> scheduleSpeakTimeout(long delayMs, Runnable onFireStateTask) {
        if (delayMs <= 0) return null;
        final long gen = generation;
        final String uid = expectedUtteranceId;
        try {
            return timeoutScheduler.schedule(() -> runInState(() -> {
                if (closed || generation != gen || uid == null || !uid.equals(expectedUtteranceId)) return;
                onFireStateTask.run();
            }), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!closed) Log.w(TAG, "[Voice] timeout scheduler 拒绝");
            return null;
        }
    }

    private void handleSpeechStartTimeout() {
        if (state.current() != VoiceSessionState.State.LISTENING) return;
        Log.i(TAG, "[Voice] speech-start timeout, no command heard; returning to IDLE and re-arming wake");
        metrics.onTimeout("SPEECH_START", "LISTENING");
        failAndCleanup("SPEECH_START_TIMEOUT");
    }

    private void handleMaxSpeechTimeout() {
        if (state.current() != VoiceSessionState.State.LISTENING) return;
        Log.i(TAG, "[Voice] 最大说话时长,强制 endpoint");
        metrics.onTimeout("MAX_SPEECH", "LISTENING");
        asrPort.finish(); // 强制 Vosk 产 final(不丢识别内容),onFinal 经 runInState 排队
        onEndpoint(expectedAsrSid); // transit ENDPOINTING + 消费 pendingFinal(或排 finalWait)
    }

    private void handleFinalWaitTimeout() {
        if (state.current() != VoiceSessionState.State.ENDPOINTING) return;
        Log.i(TAG, "[Voice] final 等待超时,本轮 ASR 失败");
        metrics.onTimeout("FINAL_WAIT", "ENDPOINTING");
        consumeFinal(new FinalTranscript("", currentLang, 0f, false));
    }

    private void cancelAllTimeouts() {
        cancelSpeechStartAndMax();
        cancelFinalWait();
        cancelFuture(speakTimeoutFuture);
        speakTimeoutFuture = null;
        cancelFuture(ttsReadyFuture);
        ttsReadyFuture = null;
    }

    private void cancelSpeechStartAndMax() {
        cancelFuture(speechStartFuture);
        speechStartFuture = null;
        cancelFuture(maxSpeechFuture);
        maxSpeechFuture = null;
    }

    private void cancelFinalWait() {
        cancelFuture(finalWaitFuture);
        finalWaitFuture = null;
    }

    private static void cancelFuture(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    // ---- 外部触发 ----

    public void onBargeIn() {
        runInState(() -> {
            if (transit(VoiceEvent.bargeIn()) != VoiceSessionState.State.BARGE_IN_LISTENING) {
                Log.w(TAG, "[Voice] onBargeIn 非 SPEAKING 态,忽略");
                return;
            }
            metrics.onCancel("BARGE_IN");
            expectedUtteranceId = null;
            ttsPort.stop();
            focusPort.release();
            cancelFuture(speakTimeoutFuture);
            speakTimeoutFuture = null;
            if (currentToken != null) currentToken.cancel();
            pendingFinal = null;
            clearPendingTts(); // 替换 pendingSpeak=null + 取消 ready 超时
            repromptCount = 0; // barge-in 开启新一轮识别,重置复述计数(防继承旧会话 REPROMPT_EXCEEDED)
            if (!startAsr()) return;
            transit(VoiceEvent.captureStarted());
            startListeningTimeouts();
        });
    }

    public void onCancel() {
        runInState(() -> {
            if (transit(VoiceEvent.cancel()) != VoiceSessionState.State.CANCELLED) {
                Log.w(TAG, "[Voice] onCancel 当前态不可取消,忽略");
                return;
            }
            metrics.onCancel("USER");
            cleanupAndIdle();
        });
    }

    /** 采音运行期错误(权限撤销/设备死亡/read 负错误码)上报 → fail-closed 回 IDLE。 */
    public void onCaptureError(String code) {
        onCaptureError(code, null);
    }

    /**
     * 同上,带清理完成回调:任务体先清理(端口 stop/KWS 重建),finally 才回调 {@code onCleanedUp},
     * 参数为清理是否成功({@code handleCaptureError} 正常返回=true;清理路径抛异常=false,端口状态未知)。
     * 供 Runtime 在端口清理成功后再重启采音——stateExecutor 积压时清理可能滞后于固定延迟,
     * 新 AudioRecord 会先向旧(可能已损坏,如 CAPTURE_PIPELINE_ERROR)端口喂音频,恢复再次失败误触熔断;
     * 失败时调用方应取消自动重试。closed/提交被拒时同步回调(true,shutdown 流程接管清理),保证调用方不悬挂。
     */
    public void onCaptureError(String code, java.util.function.Consumer<Boolean> onCleanedUp) {
        if (closed) {
            if (onCleanedUp != null) onCleanedUp.accept(true);
            return;
        }
        try {
            stateExecutor.execute(() -> {
                boolean success = false;
                try {
                    if (!closed) handleCaptureError(code);
                    success = true;
                } catch (RuntimeException e) {
                    // 端口 stop/KWS 重建抛异常:上报失败,调用方停自动恢复(不吞成成功重启采音)
                    Log.e(TAG, "[Voice] 采音清理失败: " + e.getClass().getSimpleName());
                } finally {
                    if (onCleanedUp != null) onCleanedUp.accept(success);
                }
            });
        } catch (RejectedExecutionException e) {
            if (!closed) throw e;
            if (onCleanedUp != null) onCleanedUp.accept(true); // 已关闭:直接回调收尾
        }
    }

    private void handleCaptureError(String code) {
        Log.w(TAG, "[Voice] 采音错误: " + code);
        metrics.onError(code, "CAPTURE");
        // pipeline 错误可能损坏 KWS/ASR recognizer,先 stop 关闭(cleanupAndIdle 的 start 会重建)。
        if ("CAPTURE_PIPELINE_ERROR".equals(code)) {
            invalidateAndStopAsr(); // 采音 pipeline 错误路径同样先作废 sid 再 stop(对齐 P3)
            wakePort.stop();
        }
        failAndCleanup(code);
    }

    public void manualWake() {
        manualWake("manual");
    }

    public void manualWake(String source) {
        runInState(() -> acceptWake(source)); // 手动/系统唤醒不经 KWS 代次校验;acceptWake 内已查 IDLE
    }

    private VoiceSessionState.State transit(VoiceEvent event) {
        VoiceSessionState.State from = state.current();
        VoiceSessionState.State to = state.transit(event);
        long now = System.nanoTime();
        metrics.onStateTransition(from, to, now - lastStateNanos);
        lastStateNanos = now;
        VoiceSessionListener l = uiListener;
        if (l != null) l.onStateChanged(to);
        return to;
    }
}
