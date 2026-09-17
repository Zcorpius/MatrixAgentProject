package com.matrix.agent.voice;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.task.identity.InputSource;
import com.matrix.agent.voice.port.AsrPort;
import com.matrix.agent.voice.AgentRunner;
import com.matrix.agent.voice.port.AudioFocusPort;
import com.matrix.agent.voice.NoopVoiceMetrics;
import com.matrix.agent.voice.VoiceMetricsPort;
import com.matrix.agent.voice.VoiceSessionListener;
import com.matrix.agent.voice.FinalTranscript;
import com.matrix.agent.voice.ResponsePresenter;
import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.port.TtsPort;
import com.matrix.agent.voice.VadEvent;
import com.matrix.agent.voice.port.VadPort;
import com.matrix.agent.voice.VoiceAgentRequest;
import com.matrix.agent.voice.VoicePolicyConfig;
import com.matrix.agent.voice.VoiceSessionState;
import com.matrix.agent.voice.port.WakeWordPort;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link VoiceSessionController} JVM 测试。
 *
 * <p>fake 端口(手动触发 listener)+ lambda {@link AgentRunner} + {@link FakeTimeoutScheduler}。
 * 正常流用 {@link #fireFinalThenEndpoint(String)}(final 先、endpoint 后)驱动到 SPEAKING/THINKING。
 *
 * <p>FakeTtsPort 默认 ready=true 且 setListener 立即 onReady(现有用例零退化);
 * {@link #newControllerTtsNotReady(AgentRunner)} 构造 TTS 未就绪场景测 ready 等待/超时。
 */
public final class VoiceSessionControllerTest {
    private final ResponsePresenter presenter = new ResponsePresenter();
    private FakeWakeWordPort wake;
    private FakeAsrPort asr;
    private FakeTtsPort tts;
    private FakeVadPort vad;
    private FakeAudioFocusPort focus;
    private FakeTimeoutScheduler timeoutScheduler;
    private FakeMetrics metrics;

    private VoiceSessionController newController(AgentRunner runner, Executor stateExec, Executor agentExec) {
        return newController(runner, stateExec, agentExec, true);
    }

    /** 可指定 TTS 是否就绪(测 ready 等待/超时用 false)。 */
    private VoiceSessionController newController(AgentRunner runner, Executor stateExec, Executor agentExec,
            boolean ttsReady) {
        wake = new FakeWakeWordPort();
        asr = new FakeAsrPort();
        tts = new FakeTtsPort();
        tts.ready = ttsReady;
        vad = new FakeVadPort();
        focus = new FakeAudioFocusPort();
        timeoutScheduler = new FakeTimeoutScheduler();
        metrics = new FakeMetrics();
        VoiceSessionController c = new VoiceSessionController(
                wake, asr, tts, vad, focus, runner, presenter,
                VoicePolicyConfig.defaults(), stateExec, agentExec, timeoutScheduler, metrics);
        c.start();
        return c;
    }

    private VoiceSessionController newControllerTtsNotReady(AgentRunner runner) {
        return newController(runner, direct(), direct(), false);
    }

    private static Executor direct() {
        return r -> r.run();
    }

    /** 模拟 Vosk 同帧时序:final 先到(LISTENING 缓存)、endpoint 后到(消费)。 */
    private void fireFinalThenEndpoint(String text) {
        asr.fireFinal(text);
        vad.fireEndpoint(asr.currentSid);
    }

    // ---- 正常流 ----

    @Test
    public void wakeFlow_toSpeaking_thenIdle() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertEquals("已完成。", tts.lastSpoken.getText());
        tts.fireDone();
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void wakeFlow_singleCapability_usesFriendlyName() {
        AgentRunner runner = req -> outcome(TaskState.SUCCEEDED, StopReason.DONE,
                successObs("climate.set_temperature"));
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertEquals("已调整空调温度。", tts.lastSpoken.getText());
    }

    @Test
    public void unknownConfidence_failClosed_repromptNotAgent() {
        // P1-4: confidenceAvailable=false(未知置信度)→REJECT(fail-closed),不送 Agent,复述回 LISTENING
        AgentRunner runner = req -> { throw new AssertionError("未知置信度不应调 runner"); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireFinal(new FinalTranscript("模糊", "zh-CN", 0f, false)); // 未知置信度
        vad.fireEndpoint(asr.currentSid);
        assertEquals("未知置信度应 fail-closed 复述回 LISTENING,不送 Agent",
                VoiceSessionState.State.LISTENING, c.currentState());
    }

    @Test
    public void pendingTts_focusLoss_thenReady_noStaleSpeakInIdle() {
        // P1-1: SPEAKING+pendingSpeak(TTS未ready) → focus loss → IDLE 清 pending;迟到 onTtsReady 不在 IDLE 播报
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newControllerTtsNotReady(runner);
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        focus.fireFocusLoss();
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        tts.setReady(true); // 迟到 ready
        assertNull("focus loss 后 ready 不应播报已清的 pending", tts.lastSpoken);
    }

    @Test
    public void pendingTts_cancel_clearsReadyTimeout() {
        // P1-2: cancel(cleanupAndIdle) 应取消 ttsReadyFuture,防旧 ready timeout 跨会话误杀
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newControllerTtsNotReady(runner);
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        int before = timeoutScheduler.pendingCount();
        assertTrue("SPEAKING(TTS未ready) 应排 ready timeout", before >= 1);
        c.onCancel();
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertTrue("cancel 应取消 ready timeout", timeoutScheduler.pendingCount() < before);
    }

    @Test
    public void pendingTts_readyTimeout_fires_backToIdle() {
        // P2-3: SPEAKING+pendingSpeak(TTS未ready) 排 ready timeout → fire → TTS_READY_TIMEOUT → IDLE
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newControllerTtsNotReady(runner);
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertTrue("应排 ready timeout", timeoutScheduler.pendingCount() >= 1);
        timeoutScheduler.fireAll();
        assertEquals("ready 超时应 TTS_READY_TIMEOUT 回 IDLE", VoiceSessionState.State.IDLE, c.currentState());
        // P2-2: 副作用断言——metrics 记 timeout / tts stop / KWS 重启 / 迟到 onReady 不播报
        assertTrue("metrics 应记 TTS_READY timeout", metrics.timeoutCount >= 1);
        assertTrue("应 stop TTS", tts.stopCalled);
        assertTrue("应重启 KWS(不永久耳聋)", wake.started);
        tts.setReady(true); // 迟到 ready:pendingSpeak 已清,不应播报
        assertNull("迟到 onReady 不应在 timeout 后播报", tts.lastSpoken);
    }

    @Test
    public void emptyFinal_thenEndpoint_validatorRejects_repromptBackToListening() {
        AgentRunner runner = req -> { throw new AssertionError("空文本不应调 runner"); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("");
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        assertEquals(1, c.repromptCount());
    }

    // ---- VAD 端点 / final 乱序----

    @Test
    public void finalArrivesBeforeEndpoint_cachedThenConsumed() {
        final VoiceAgentRequest[] captured = new VoiceAgentRequest[1];
        AgentRunner runner = req -> { captured[0] = req; return succeededOutcome(); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireFinal("开空调");
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        assertNull(captured[0]);
        vad.fireEndpoint(asr.currentSid);
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertEquals("开空调", captured[0].transcript().text());
    }

    @Test
    public void endpointArrivesBeforeFinal_finalWaitThenFinal() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        vad.fireEndpoint(asr.currentSid);
        assertEquals(VoiceSessionState.State.ENDPOINTING, c.currentState());
        assertTrue(timeoutScheduler.pendingCount() >= 1);
        asr.fireFinal("开空调");
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
    }

    @Test
    public void finalIgnored_inRecognizing() {
        final int[] count = {0};
        AgentRunner runner = req -> { count[0]++; return succeededOutcome(); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        asr.fireFinal("再来一次");
        assertEquals(1, count[0]);
    }

    // ---- barge-in ----

    @Test
    public void bargeIn_duringSpeaking() {
        final CancellationToken[] tokenHolder = new CancellationToken[1];
        AgentRunner runner = req -> {
            tokenHolder[0] = req.cancellationToken();
            return succeededOutcome();
        };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        c.onBargeIn();
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        assertTrue("TTS 应停止", tts.stopCalled);
        assertTrue("execute 的 token 应被 cancel", tokenHolder[0].isCancelled());
    }

    // ---- 取消(异步 runner)----

    @Test
    public void cancel_duringThinking() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch aborted = new CountDownLatch(1);
        final CancellationToken[] tokenHolder = new CancellationToken[1];
        AgentRunner runner = req -> {
            CancellationToken token = req.cancellationToken();
            tokenHolder[0] = token;
            token.registerAbortHook(aborted::countDown);
            entered.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            return cancelledOutcome();
        };
        ExecutorService agentService = Executors.newSingleThreadExecutor();
        VoiceSessionController c = newController(runner, direct(), agentService);
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertTrue("runner 应进入", entered.await(1, TimeUnit.SECONDS));
        assertEquals(VoiceSessionState.State.THINKING, c.currentState());
        c.onCancel();
        assertTrue("token.cancel 应触发 abort hook", aborted.await(1, TimeUnit.SECONDS));
        release.countDown();
        agentService.submit(() -> { }).get(2, TimeUnit.SECONDS);
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertTrue(tokenHolder[0].isCancelled());
        agentService.shutdownNow();
    }

    // ---- 会话超时 watchdog----

    @Test
    public void speechStartTimeout_noPartial_backToIdle() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        timeoutScheduler.fireAll();
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertTrue("超时回 IDLE 应重启唤醒", wake.started);
    }

    @Test
    public void maxSpeechTimeout_forcesEndpoint() {
        final VoiceAgentRequest[] captured = new VoiceAgentRequest[1];
        AgentRunner runner = req -> { captured[0] = req; return succeededOutcome(); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.firePartial("嗯");             // 取消 speechStart,保留 maxSpeech
        asr.enqueueFinishResult("开空调");  // 模拟 Vosk getFinalResult flush 应产出的结果
        // 不预置 fireFinal:maxSpeech 超时必须靠 asr.finish() 强制产 final,而非测试替它注入
        timeoutScheduler.fireAll();
        assertTrue("maxSpeech 超时应调用 asr.finish()", asr.finishCalled);
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertEquals("应提交 finish 强制产出的 final", "开空调", captured[0].transcript().text());
    }

    @Test
    public void staleWake_fromOldEpoch_dropped_notEnterListening() {
        // P2: KWS 重建(采音错误清理的 stop+start 窗口)后,旧 recognizer 锁外派发的迟到唤醒
        // (旧代次)不得进入新会话;当前代次正常进(阳性对照)
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake(); // 当前代次 → LISTENING
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        long oldEpoch = wake.epoch;
        c.onCancel(); // cleanup → startWakeKws 重建 → expectedWakeEpoch 更新为新代次
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        wake.fireWake(oldEpoch); // 旧 recognizer 迟到唤醒
        assertEquals("旧代次唤醒不得进入 LISTENING", VoiceSessionState.State.IDLE, c.currentState());
        wake.fireWake(); // 当前代次 → 正常进入
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
    }

    @Test
    public void staleWakeError_fromOldEpoch_dropped_noRetryScheduled() {
        // P2: 旧代次的迟到 onError 不触发退避重建(不排 wakeRetry 任务、不打错误指标)
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake(); // → LISTENING(KWS 已停)
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        long oldEpoch = wake.epoch; // 布防时的代次
        c.onCancel(); // LISTENING 可取消 → cleanup → startWakeKws 重建 → expectedWakeEpoch 更新
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        int before = timeoutScheduler.pendingCount();
        int errorsBefore = metrics.errorCount;
        wake.fireError("VOSK_WAKE_ERR", oldEpoch);
        assertEquals("旧代次 onError 不应排重建任务", before, timeoutScheduler.pendingCount());
        assertEquals("旧代次 onError 不应记错误指标", errorsBefore, metrics.errorCount);
        // 当前代次错误正常处理(阳性对照:排重建)
        wake.fireError("VOSK_WAKE_ERR");
        assertTrue("当前代次 onError 应排重建", timeoutScheduler.pendingCount() > before);
    }

    @Test
    public void captureError_failClosedToIdle() {
        // 采音运行期错误(权限撤销 / read 负错误码)上报 → fail-closed 回 IDLE。
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        c.onCaptureError("CAPTURE_READ_ERROR_-6");
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void captureError_cleanupCallback_firesOnlyAfterCleanup() throws Exception {
        // P2(Runtime 重启时序):onCaptureError(code, onCleanedUp) 的回调必须晚于清理完成——
        // stateExecutor 积压时端口 stop/KWS 重建滞后,Runtime 只能在回调后排重试,
        // 保证新 AudioRecord 不在清理完成前向旧(可能已损坏)端口喂音频
        ExecutorService stateExec = Executors.newSingleThreadExecutor();
        CountDownLatch gate = new CountDownLatch(1);
        // 占住单线程 executor 制造积压:c.start() 与清理任务都排在 gate 之后
        stateExec.submit(() -> {
            try { gate.await(); } catch (InterruptedException ignored) { }
        });
        try {
            AgentRunner runner = req -> succeededOutcome();
            wake = new FakeWakeWordPort();
            asr = new FakeAsrPort();
            tts = new FakeTtsPort();
            vad = new FakeVadPort();
            focus = new FakeAudioFocusPort();
            timeoutScheduler = new FakeTimeoutScheduler();
            metrics = new FakeMetrics();
            VoiceSessionController c = new VoiceSessionController(
                    wake, asr, tts, vad, focus, runner, presenter,
                    VoicePolicyConfig.defaults(), stateExec, direct(), timeoutScheduler, metrics);
            c.start(); // 排队;执行时 startWakeKws 一次(startCount=1)
            CountDownLatch callbackDone = new CountDownLatch(1);
            final int[] wakeStartsAtCallback = {-1};
            c.onCaptureError("CAPTURE_READ_ERROR_-6", success -> {
                assertTrue("正常清理应上报成功", success);
                wakeStartsAtCallback[0] = wake.startCount; // 回调时点的清理进度快照
                callbackDone.countDown();
            });
            // 积压期间:清理任务未执行 → 回调绝不触发(Runtime 侧即 capture.start() 绝不会被排)
            assertEquals("积压期间回调不应触发", 1, callbackDone.getCount());
            assertEquals("积压期间清理任务(含 start 任务)不应执行", 0, wake.startCount);
            gate.countDown(); // 放行
            assertTrue("清理完成后应触发回调", callbackDone.await(2, TimeUnit.SECONDS));
            // 回调触发时清理已完成:c.start() 布防 KWS 一次 + 采音错误 cleanup 重建一次 → =2
            assertEquals("回调必须晚于清理(KWS 已重建)", 2, wakeStartsAtCallback[0]);
            assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        } finally {
            stateExec.shutdownNow();
        }
    }

    @Test
    public void bargeInAsrStartFail_idles() {
        // barge-in 中 ASR start 失败:不卡 BARGE_IN_LISTENING,经 error→ERROR_ANNOUNCING→reset→IDLE。
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // → SPEAKING
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        asr.failNextStart = true;
        c.onBargeIn(); // SPEAKING→BARGE_IN_LISTENING→startAsr 失败→failAndCleanup
        assertEquals("barge-in 中 ASR start 失败应收敛回 IDLE", VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void finalWaitTimeout_emptyFinal_reprompt() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        vad.fireEndpoint(asr.currentSid);
        assertEquals(VoiceSessionState.State.ENDPOINTING, c.currentState());
        timeoutScheduler.fireAll();
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        assertEquals(1, c.repromptCount());
    }

    @Test
    public void firstNonEmptyPartial_cancelsSpeechStart() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        assertEquals(2, timeoutScheduler.pendingCount()); // speechStart + maxSpeech
        asr.firePartial("开");
        assertEquals("首非空 partial 应取消 speechStart", 1, timeoutScheduler.cancelledCount());
    }

    @Test
    public void staleTimeout_afterCancelAndRewake_ignored() {
        // onWake 现在检查 IDLE,manualWake 在 LISTENING 忽略(不增 gen)。
        // generation 隔离改用 cancel 回 IDLE + rewake 验证。
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();                  // gen=1, 排 speechStart/maxSpeech(gen=1)
        FakeTask stale = timeoutScheduler.tasks.get(0);
        c.onCancel();                     // 回 IDLE
        wake.fireWake();                  // gen=2(onWake IDLE→OK)
        stale.command.run();              // gen=1 vs gen=2 → 丢弃
        assertNotEquals("stale timeout 不应把状态带偏", VoiceSessionState.State.IDLE, c.currentState());
    }

    // ---- 复述计数与上限----

    @Test
    public void repromptTwice_thenAbort() {
        AgentRunner runner = req -> { throw new AssertionError("空文本不应调 runner"); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("");
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        assertEquals(1, c.repromptCount());
        fireFinalThenEndpoint("");
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertTrue("超限回 IDLE 应重启唤醒", wake.started);
    }

    @Test
    public void validTranscript_afterOneReprompt_accepts() {
        final VoiceAgentRequest[] captured = new VoiceAgentRequest[1];
        AgentRunner runner = req -> { captured[0] = req; return succeededOutcome(); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("");
        assertEquals(1, c.repromptCount());
        fireFinalThenEndpoint("开空调");
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertEquals("开空调", captured[0].transcript().text());
    }

    // ---- 音频焦点 + TTS 生命周期----

    @Test
    public void focusDenied_noSpeak_backToIdle() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        focus.requestGranted = false;
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // onTerminal:request false → error → cleanup → IDLE
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertNull("焦点拒绝不应播报", tts.lastSpoken);
    }

    @Test
    public void focusLoss_duringSpeaking_stopsTts_idle_restartsWake() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // → SPEAKING
        focus.fireFocusLoss();          // stop TTS + IDLE + 重启 KWS(不永久耳聋)
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertTrue("TTS 应停止", tts.stopCalled);
        assertTrue("P1-7:focus loss 应重启 KWS", wake.started);
    }

    @Test
    public void ttsReady_wait_thenSpeak() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newControllerTtsNotReady(runner);
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // onTerminal:ttsReady=false → pendingSpeak
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertNull("TTS 未就绪不应立即播报", tts.lastSpoken);
        assertEquals("TTS 未就绪不应抢 AudioFocus(P2-2)", 0, focus.requestCount);
        tts.setReady(true);              // onReady → flush pendingSpeak → speakNow(抢 focus)
        assertNotNull("就绪后应播报缓存的第一次结果", tts.lastSpoken);
        assertEquals("已完成。", tts.lastSpoken.getText());
        assertEquals("speakNow 应抢一次 AudioFocus(P2-2)", 1, focus.requestCount);
    }

    @Test
    public void speak_syncError_directExecutor_notDropped() {
        // P2-1: speak() 内同步 onError 在 direct executor 下不丢(expectedUtteranceId 已 speak 前设)
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct()); // direct stateExecutor
        tts.syncFailNextSpeak = true;
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // SPEAKING → speakNow → speak(同步 onError) → failAndCleanup
        assertEquals("speak 同步 onError 应被处理(direct executor 不丢)→ IDLE",
                VoiceSessionState.State.IDLE, c.currentState());
        // P3: 同步失败收敛后不应遗留 speak watchdog
        assertEquals("speak 同步失败不应遗留 watchdog", 0, timeoutScheduler.pendingCount());
    }

    @Test
    public void cancel_staleAsrCallbacks_dropped_noUiOrSideEffects() {
        // P3-1: cancel 作废 expectedAsrSid 后,旧采音线程已取出的 partial/onError 全部丢弃
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake(); // → LISTENING,expectedAsrSid=当前
        final String[] partialSeen = {null};
        tts.listener = null; // 不关心 TTS
        // 用 uiListener 捕获旧 partial 是否上浮
        c.setUiListener(new VoiceSessionListener() {
            @Override public void onPartial(String text) { partialSeen[0] = text; }
        });
        long staleSid = asr.currentSid;
        c.onCancel(); // → IDLE,expectedAsrSid 作废
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        int startCountBefore = wake.startCount;
        asr.firePartial("旧会话partial"); // 旧采音线程迟到的 partial(带作废前 sid)
        asr.fireError("STALE_ASR_ERROR"); // 旧 onError(不应重复清理/重启 KWS)
        assertNull("旧 partial 不应更新 UI", partialSeen[0]);
        assertEquals("旧 onError 不应改变状态", VoiceSessionState.State.IDLE, c.currentState());
        assertTrue("旧 onError 不应破坏 KWS(IDLE 下在跑)", wake.started);
        assertEquals("旧 onError 不应触发额外 cleanup+KWS restart", startCountBefore, wake.startCount);
    }

    @Test
    public void successfulFinal_staleAsrError_doesNotKillSpeaking() {
        // P2: 正常成功路径 consumeFinal 也作废 sid——成功 final 进 SPEAKING 后,旧 sid 的迟到
        // onAsrError 不得命中(不清理回 IDLE、不 stop TTS)
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // → SPEAKING(consumeFinal 已作废旧 sid)
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        int startCountBefore = wake.startCount;
        boolean ttsStopped = tts.stopCalled;
        asr.fireError("STALE_ASR_ERROR"); // 旧 sid 迟到 onError
        assertEquals("旧 sid onError 不应杀掉 SPEAKING", VoiceSessionState.State.SPEAKING, c.currentState());
        assertEquals("旧 sid onError 不应 stop TTS", ttsStopped, tts.stopCalled);
        assertEquals("旧 sid onError 不应触发 KWS restart", startCountBefore, wake.startCount);
    }

    @Test
    public void captureError_cleanupFails_callbackReportsFailure() {
        // P2: 清理路径(端口 stop/KWS 重建)抛运行时异常 → 回调上报 success=false,
        // Runtime 据此取消自动重试(不得在端口状态未知时重启采音)
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        assertEquals(VoiceSessionState.State.LISTENING, c.currentState());
        asr.failOnStop = true; // pipeline 清理先走 invalidateAndStopAsr → asrPort.stop() 抛
        final Boolean[] reported = {null};
        c.onCaptureError("CAPTURE_PIPELINE_ERROR", success -> reported[0] = success);
        assertEquals("清理失败应回调 false", Boolean.FALSE, reported[0]);
        assertEquals("failAndCleanup 未执行完,状态不应被收敛", VoiceSessionState.State.LISTENING, c.currentState());
    }

    @Test
    public void syncErrorInsideAsrStop_afterFinal_dropped() {
        // P3: consumeFinal 先作废 sid 再 stop();端口契约允许 stop() 内同步 onError——
        // 若 stop 在作废前,该同步错误会命中当前会话误杀 THINKING/SPEAKING
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.syncErrorOnStop = true; // stop() 内同步 onError(带当前 sid)
        fireFinalThenEndpoint("开空调"); // consumeFinal → invalidateAndStopAsr → stop() 内同步 onError → sid 已作废,丢弃
        assertEquals("stop 内同步 onError 不应误杀,应继续到 SPEAKING",
                VoiceSessionState.State.SPEAKING, c.currentState());
        assertNotNull("会话不应被 stop 内错误打断", tts.lastSpoken);
    }

    @Test
    public void focusRequest_reentrantLoss_doesNotSpeakWithoutFocus() {
        // P3-2: request() 重入触发 focus loss → IDLE;speakNow 确认后不播、释放焦点
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        focus.syncLossOnRequest = true; // request() 内同步 focus loss
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // → SPEAKING → speakNow → request(重入 loss→IDLE) → 确认不 SPEAKING → 不播
        assertEquals("重入 focus loss 后应回 IDLE", VoiceSessionState.State.IDLE, c.currentState());
        assertNull("无焦点不应播报", tts.lastSpoken);
        assertTrue("不播报应释放焦点", focus.releaseCount >= 1);
    }

    @Test
    public void speakTimeout_stopsAndIdle() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // → SPEAKING + 排 speak 超时
        timeoutScheduler.fireAll();     // speak 超时 → cleanup → IDLE
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertTrue("TTS 应停止", tts.stopCalled);
    }

    @Test
    public void focusReleased_onTtsDone() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // SPEAKING(request focus,未 release)
        assertEquals(0, focus.releaseCount);
        tts.fireDone();                  // → release focus + IDLE
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
        assertEquals("onTtsDone 应 release focus", 1, focus.releaseCount);
    }

    // ---- 错误路径 ----

    @Test
    public void ttsError_toErrorAnnouncing_thenIdle() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        tts.fireError("TTS_FAIL");
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void agentRunner_exception_recoversToIdle() {
        AgentRunner runner = req -> { throw new RuntimeException("boom"); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void asrError_recoversToIdle() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        asr.fireError("ASR_FAIL");
        assertEquals(VoiceSessionState.State.IDLE, c.currentState());
    }

    @Test
    public void shutdown_thenLateTtsDone_doesNotRestartWake() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertFalse("SPEAKING 时 wake 应已停", wake.started);
        c.shutdown(null);
        assertFalse("shutdown 后 wake 应停", wake.started);
        tts.fireDone();
        assertFalse("迟到 onTtsDone 不应重启 wake", wake.started);
    }

    @Test
    public void wakeStartFail_thenRebuildSucceeds_clearsRetryAndRearms() {
        // 第九轮批B: WakeWordPort.start() 返回结构化结果,重建成功立即清零 wakeRetryCount(不等用户说唤醒词)
        AgentRunner runner = req -> succeededOutcome();
        wake = new FakeWakeWordPort();
        wake.failNextStart = true; // 初始 KWS start 即失败
        asr = new FakeAsrPort();
        tts = new FakeTtsPort();
        vad = new FakeVadPort();
        focus = new FakeAudioFocusPort();
        timeoutScheduler = new FakeTimeoutScheduler();
        metrics = new FakeMetrics();
        VoiceSessionController c = new VoiceSessionController(
                wake, asr, tts, vad, focus, runner, presenter,
                VoicePolicyConfig.defaults(), direct(), direct(), timeoutScheduler, metrics);
        c.start();
        // 初始 start 失败 → 退避重建(count=1),wake 未布防,但单次失败不应熔断
        assertFalse("初始 start 失败应未布防 KWS", wake.started);
        assertTrue("应排一次重建任务", timeoutScheduler.pendingCount() >= 1);
        assertFalse("单次失败不应报 WAKE_UNAVAILABLE", metrics.hasError("WAKE_UNAVAILABLE"));

        // 重建成功 → 立即清零计数 + 重新布防,不报 WAKE_UNAVAILABLE(修复核心:成功即清零)
        wake.failNextStart = false;
        timeoutScheduler.fireAll();
        assertTrue("重建成功应重新布防 KWS", wake.started);
        assertFalse("重建成功不应报 WAKE_UNAVAILABLE", metrics.hasError("WAKE_UNAVAILABLE"));
    }

    @Test
    public void shutdown_thenExecutorShutdown_lateCallback_noRejected_noRestartWake() throws Exception {
        ExecutorService stateExec = Executors.newSingleThreadExecutor();
        ExecutorService agentExec = Executors.newSingleThreadExecutor();
        try {
            AgentRunner runner = req -> succeededOutcome();
            wake = new FakeWakeWordPort();
            asr = new FakeAsrPort();
            tts = new FakeTtsPort();
            FakeVadPort vad2 = new FakeVadPort();
            FakeAudioFocusPort focus2 = new FakeAudioFocusPort();
            VoiceSessionController c = new VoiceSessionController(
                    wake, asr, tts, vad2, focus2, runner, presenter,
                    VoicePolicyConfig.defaults(), stateExec, agentExec, new FakeTimeoutScheduler(),
                    NoopVoiceMetrics.INSTANCE);
            c.start();
            awaitWakeStarted(wake); // P2(epoch):KWS 先布防(代次生效)再触发唤醒——真实链路事件仅在引擎启动后产生
            wake.fireWake();
            awaitState(c, VoiceSessionState.State.LISTENING, 2000); // onWake 在异步 stateExec,跑完再喂 final
            asr.fireFinal("开空调");
            vad2.fireEndpoint(asr.currentSid);
            awaitState(c, VoiceSessionState.State.SPEAKING, 2000);
            c.shutdown(null);
            stateExec.shutdown();
            tts.fireDone();
            assertFalse("迟到回调不应重启 wake", wake.started);
        } finally {
            agentExec.shutdownNow();
        }
    }

    private static void awaitState(VoiceSessionController c, VoiceSessionState.State expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (c.currentState() == expected) return;
            Thread.sleep(10);
        }
        throw new AssertionError("超时未到 " + expected + ", 当前 " + c.currentState());
    }

    /** 等待 KWS 布防完成(异步 stateExec 下 start() 尚未执行时,唤醒事件的 epoch 会早于布防被丢弃)。 */
    private static void awaitWakeStarted(FakeWakeWordPort wake) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && !wake.started) Thread.sleep(10);
        assertTrue("KWS 应已布防", wake.started);
    }

    // ---- 语音元数据贯通 ----

    @Test
    public void voiceRequest_carriesVoiceMetadata_toRunner() {
        final VoiceAgentRequest[] captured = new VoiceAgentRequest[1];
        AgentRunner runner = req -> { captured[0] = req; return succeededOutcome(); };
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertNotNull(captured[0]);
        assertEquals("开空调", captured[0].transcript().text());
        assertEquals(InputSource.VOICE, captured[0].inputSource());
        assertEquals(Actor.DRIVER, captured[0].actor());
        assertEquals(VoiceAgentRequest.DEFAULT_DRIVER_ZONE, captured[0].audioZoneId());
        assertNotNull(captured[0].cancellationToken());
        assertTrue(captured[0].transcript().confidenceAvailable());
        assertEquals(0.9f, captured[0].transcript().confidence(), 0.001f);
    }

    @Test
    public void voiceRequest_languageTag_flowsToPresenter() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        fireFinalThenEndpoint("开空调");
        assertEquals(VoiceSessionState.State.SPEAKING, c.currentState());
        assertEquals("zh-CN", tts.lastSpoken.getLanguageTag());
    }

    // ---- 指标打点 ----

    @Test
    public void metrics_recordsWakeAndStateTransitions() {
        AgentRunner runner = req -> succeededOutcome();
        FakeMetrics m = new FakeMetrics();
        wake = new FakeWakeWordPort();
        asr = new FakeAsrPort();
        tts = new FakeTtsPort();
        vad = new FakeVadPort();
        focus = new FakeAudioFocusPort();
        timeoutScheduler = new FakeTimeoutScheduler();
        VoiceSessionController c = new VoiceSessionController(
                wake, asr, tts, vad, focus, runner, presenter,
                VoicePolicyConfig.defaults(), direct(), direct(), timeoutScheduler, m);
        c.start();
        wake.fireWake();
        fireFinalThenEndpoint("开空调"); // → SPEAKING
        tts.fireDone();                 // → IDLE
        assertTrue("应记录 wake", m.wakeCount >= 1);
        assertTrue("应记录状态迁移(含 IDLE→WAKE_ACCEPTED→…→IDLE)", m.transitions.size() >= 4);
        assertTrue("应记录 Agent 延迟", m.agentLatencyCount >= 1);
        // 隐私:transcript 不进 metrics(FakeMetrics 不收 transcript,Controller 也不传)
    }

    @Test
    public void metrics_recordsErrorAndTimeout() {
        AgentRunner runner = req -> succeededOutcome();
        VoiceSessionController c = newController(runner, direct(), direct());
        wake.fireWake();
        vad.fireEndpoint(asr.currentSid);             // 无 pending → ENDPOINTING + 排 finalWait
        timeoutScheduler.fireAll();     // finalWait 触发 → 合成空 final → REJECT → 复述 → LISTENING
        assertTrue("应记录 timeout", metrics.timeoutCount >= 1);
    }

    // ---- outcome / observation 装配 ----

    private static AgentOutcome succeededOutcome() {
        return new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L);
    }

    private static AgentOutcome cancelledOutcome() {
        return new AgentOutcome("req", TaskState.CANCELLED, StopReason.CANCELLED, new Trajectory(), 0L);
    }

    private static AgentOutcome outcome(TaskState state, StopReason reason,
            com.matrix.agent.task.ToolObservation... obs) {
        Trajectory t = new Trajectory();
        if (obs.length > 0) {
            t.addIteration(new com.matrix.agent.task.AgentIteration(1,
                    com.matrix.agent.task.AgentMessage.assistant("ok", java.util.Collections.emptyList()),
                    java.util.Collections.emptyList(),
                    java.util.Arrays.asList(obs),
                    java.util.Collections.emptyList(),
                    0L));
        }
        t.finish(reason, 0L, obs.length);
        return new AgentOutcome("req", state, reason, t, 0L);
    }

    private static com.matrix.agent.task.ToolObservation successObs(String cap) {
        com.matrix.agent.task.tool.ToolResult r = new com.matrix.agent.task.tool.ToolResult(
                com.matrix.agent.task.tool.ToolResult.Status.SUCCESS, cap, "ok",
                java.util.Collections.emptyMap(), true, 0L);
        return com.matrix.agent.task.ToolObservation.fromParts("call-1", cap, r, null, false);
    }

    // ---- fakes ----

    static final class FakeWakeWordPort implements WakeWordPort {
        WakeWordPort.Listener listener;
        boolean started;
        int startCount; // P3: start 调用计数(验证旧回调不触发额外 cleanup+KWS restart)
        boolean failNextStart; // 模拟 wake start 失败(重建退避测试)
        /** recognizer 代次(对齐 VoskWakeAdapter):start 递增,stop 作废递增。 */
        long epoch;
        private long epochSeq;
        @Override public void setListener(WakeWordPort.Listener listener) { this.listener = listener; }
        @Override public WakeWordPort.WakeStartResult start() {
            epoch = ++epochSeq;
            if (failNextStart) { failNextStart = false; return new WakeWordPort.WakeStartResult(epoch, "VOSK_WAKE_START_FAILED"); }
            started = true;
            startCount++;
            return new WakeWordPort.WakeStartResult(epoch, null);
        }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void stop() { started = false; epoch = ++epochSeq; /* 作废在途结果 */ }
        void fireWake() { fireWake(epoch); }
        /** 携带指定代次触发唤醒(旧代次→Controller 应回调校验丢弃)。 */
        void fireWake(long wakeEpoch) { if (listener != null) listener.onWake(wakeEpoch); }
        void fireError(String code) { fireError(code, epoch); }
        void fireError(String code, long wakeEpoch) { if (listener != null) listener.onError(code, wakeEpoch); }
    }

    static final class FakeAsrPort implements AsrPort {
        AsrPort.Listener listener;
        boolean started;
        boolean finishCalled;          // 验证 maxSpeech 确实调了 finish()
        FinalTranscript finishResult;  // finish() 应强制产出的 final(模拟 Vosk getFinalResult flush)
        long currentSid;               // start 时递增,回调携带(Controller 校验 sid==expectedAsrSid)
        @Override public void setListener(AsrPort.Listener listener) { this.listener = listener; }
        boolean failNextStart; // 模拟 ASR start 失败(barge-in 等异常路径测试)
        @Override public AsrPort.AsrStartResult start() {
            started = true;
            if (failNextStart) { failNextStart = false; return new AsrPort.AsrStartResult(-1, "ASR_START_FAILED"); }
            return new AsrPort.AsrStartResult(++currentSid, null);
        }
        @Override public void feed(byte[] pcm, int len) { }
        boolean failOnStop; // P2: stop() 抛运行时异常(测清理回调上报失败)
        boolean syncErrorOnStop; // P3: stop() 内同步 onError(测 sid 先作废再 stop 的顺序)
        @Override public void stop() {
            started = false;
            if (failOnStop) throw new IllegalStateException("recognizer close failed");
            if (syncErrorOnStop && listener != null) listener.onError("ASR_STOP_SYNC_ERROR", currentSid);
        }
        @Override public void finish() {
            finishCalled = true;
            if (finishResult != null && listener != null) listener.onFinal(finishResult, currentSid);
        }
        /** 预置 finish() 应强制产出的 final(模拟 Vosk getFinalResult 强制 flush)。 */
        void enqueueFinishResult(String text) { this.finishResult = new FinalTranscript(text, "zh-CN", 0.9f, true); }
        void firePartial(String text) { if (listener != null) listener.onPartial(text, currentSid); }
        void fireFinal(String text) { fireFinal(new FinalTranscript(text, "zh-CN", 0.9f, true)); }
        void fireFinal(FinalTranscript transcript) { if (listener != null) listener.onFinal(transcript, currentSid); }
        void fireError(String code) { if (listener != null) listener.onError(code, currentSid); }
    }

    static final class FakeVadPort implements VadPort {
        VadPort.Listener listener;
        @Override public void setListener(VadPort.Listener listener) { this.listener = listener; }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void reset() { }
        void fireEndpoint(long sid) { if (listener != null) listener.onEvent(VadEvent.ENDPOINT, sid); }
    }

    /** 收集指标事件(隐私——不收 transcript,Controller 也不传)。 */
    static final class FakeMetrics implements VoiceMetricsPort {
        final java.util.List<String> transitions = new java.util.ArrayList<>();
        int wakeCount = 0;
        int agentLatencyCount = 0;
        int errorCount = 0;
        final java.util.List<String> errorCodes = new java.util.ArrayList<>();
        int timeoutCount = 0;
        int cancelCount = 0;

        @Override public void onStateTransition(VoiceSessionState.State from, VoiceSessionState.State to, long durationNanos) {
            transitions.add(from + "->" + to);
        }
        @Override public void onWake(String source) { wakeCount++; }
        @Override public void onAgentLatency(long nanos) { agentLatencyCount++; }
        @Override public void onError(String code, String stage) { errorCount++; errorCodes.add(code); }
        boolean hasError(String code) { return errorCodes.contains(code); }
        @Override public void onTimeout(String type, String stage) { timeoutCount++; }
        @Override public void onCancel(String stage) { cancelCount++; }
    }

    static final class FakeAudioFocusPort implements AudioFocusPort {
        AudioFocusPort.Listener listener;
        boolean requestGranted = true;
        int releaseCount = 0;
        int requestCount = 0; // P2-2: request 调用计数(验证 focus 时机)
        boolean syncLossOnRequest; // P3-2: request() 时同步触发 focus loss(重入,测窄竞态)
        @Override public void setListener(AudioFocusPort.Listener listener) { this.listener = listener; }
        @Override public boolean request() {
            requestCount++;
            if (syncLossOnRequest && listener != null) listener.onFocusLoss();
            return requestGranted;
        }
        @Override public void release() { releaseCount++; }
        void fireFocusLoss() { if (listener != null) listener.onFocusLoss(); }
    }

    static final class FakeTtsPort implements TtsPort {
        TtsPort.Listener listener;
        SpeakableResponse lastSpoken;
        boolean stopCalled;
        boolean ready = true; // 默认就绪
        String lastUtteranceId;

        @Override
        public void setListener(TtsPort.Listener listener) {
            this.listener = listener;
            if (listener != null && ready) listener.onReady();
        }
        boolean syncFailNextSpeak; // P2-1: speak 内同步 onError(测 direct executor 下回调不丢)
        @Override public void speak(SpeakableResponse response, String utteranceId) {
            lastSpoken = response; stopCalled = false;
            lastUtteranceId = utteranceId;
            if (syncFailNextSpeak) {
                syncFailNextSpeak = false;
                if (listener != null) listener.onError(utteranceId, "TTS_SYNC_FAIL");
            }
        }
        @Override public void stop() { stopCalled = true; }
        void fireDone() { fireDone(lastUtteranceId); }
        void fireDone(String id) { if (listener != null) listener.onDone(id); }
        void fireError(String code) { fireError(lastUtteranceId, code); }
        void fireError(String id, String code) { if (listener != null) listener.onError(id, code); }
        /** 切换就绪状态;true 时同步触发 onReady。 */
        void setReady(boolean r) {
            this.ready = r;
            if (r && listener != null) listener.onReady();
        }
        void fireInitError(String code) { if (listener != null) listener.onInitError(code); }
    }

    /** 不自动触发;测试主动 fireAll。 */
    static final class FakeTimeoutScheduler implements ScheduledExecutorService {
        final List<FakeTask> tasks = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            FakeTask t = new FakeTask(command);
            tasks.add(t);
            return t;
        }

        @Override public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable command, long initialDelay, long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        void fireAll() {
            for (FakeTask t : new ArrayList<>(tasks)) {
                if (!t.cancelled) t.command.run();
            }
        }

        int pendingCount() {
            int n = 0;
            for (FakeTask t : tasks) if (!t.cancelled) n++;
            return n;
        }

        int cancelledCount() {
            int n = 0;
            for (FakeTask t : tasks) if (t.cancelled) n++;
            return n;
        }

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public <T> Future<T> submit(Callable<T> task) { throw new UnsupportedOperationException(); }
        @Override public <T> Future<T> submit(Runnable task, T result) { throw new UnsupportedOperationException(); }
        @Override public Future<?> submit(Runnable task) { throw new UnsupportedOperationException(); }
        @Override public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> List<Future<T>> invokeAll(
                Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public void execute(Runnable command) { throw new UnsupportedOperationException(); }
    }

    static final class FakeTask implements ScheduledFuture<Void> {
        final Runnable command;
        volatile boolean cancelled;
        FakeTask(Runnable command) { this.command = command; }
        @Override public boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return false; }
        @Override public Void get() { return null; }
        @Override public Void get(long timeout, TimeUnit unit) { return null; }
        @Override public long getDelay(TimeUnit unit) { return 0L; }
        @Override public int compareTo(Delayed o) { return 0; }
    }
}
