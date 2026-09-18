package com.matrix.agent.voice;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.contract.ModelTurn;

import android.app.Application;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.intent.KeywordIntentClassifier;
import com.matrix.agent.demo.MockVehicleStateSource;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.voice.port.AsrPort;
import com.matrix.agent.voice.port.AudioFocusPort;
import com.matrix.agent.voice.NoopVoiceMetrics;
import com.matrix.agent.voice.ResponsePresenter;
import com.matrix.agent.voice.port.TtsPort;
import com.matrix.agent.voice.port.VadPort;
import com.matrix.agent.voice.VoiceAssembly;
import com.matrix.agent.voice.VoiceAssemblyFactory;
import com.matrix.agent.voice.port.VoiceCapturePort;
import com.matrix.agent.voice.VoicePolicyConfig;
import com.matrix.agent.voice.VoiceSessionListener;
import com.matrix.agent.voice.VoiceSessionState;
import com.matrix.agent.voice.port.WakeWordPort;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.data.audit.NoopAuditRepository;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link VoiceRuntime} JVM 测试:两层。
 *
 * <p>① 纯逻辑 seam:采音启动屏障 {@link VoiceRuntime#captureStartAllowed} 表驱动。
 *
 * <p>② 端到端恢复路径(publishForTest 注入真 Runtime + 真 Controller(假端口)+ 假 capture,
 * 真 executor/scheduler,重试延迟为真实 500/1000/1500ms):
 * <ul>
 *   <li>异常清理 pending 期间 pause→resume 不得 start(恢复屏障,清理完成后才恢复);</li>
 *   <li>清理成功后采音恰好重启一次(不重复);</li>
 *   <li>清理失败(端口 stop 抛异常)后不再自动重试,resume 也不得绕过;</li>
 *   <li>busy 重试有界(3 次耗尽报失败),pause 取消重试链。</li>
 * </ul>
 */
public final class VoiceRuntimeTest {

    // ---- 屏障表驱动 ----

    @Test
    public void captureStartAllowed_allClear_true() {
        assertTrue(VoiceRuntime.captureStartAllowed(false, true, false, false, false));
    }

    @Test
    public void captureStartAllowed_blockedByTerminalStates() {
        assertFalse("已销毁不得启动", VoiceRuntime.captureStartAllowed(true, true, false, false, false));
        assertFalse("后台不得启动", VoiceRuntime.captureStartAllowed(false, false, false, false, false));
        assertFalse("已有采音不得重复启动", VoiceRuntime.captureStartAllowed(false, true, true, false, false));
    }

    @Test
    public void captureStartAllowed_blockedByRecoveryBarrier() {
        // P2 核心:异常清理排队期间(pending),即使前台且无采音,resume/重试也不得启动新采音
        assertFalse("清理未完成不得启动(pause→resume 不得绕过)",
                VoiceRuntime.captureStartAllowed(false, true, false, true, false));
        // 清理失败:端口状态未知,停自动恢复,等用户重进页面
        assertFalse("清理失败不得自动恢复",
                VoiceRuntime.captureStartAllowed(false, true, false, false, true));
    }

    // ---- 端到端恢复路径 ----

    @Test
    public void recoveryPending_pauseResume_doesNotStartCapture() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runtime.resume(); // 置前台(captureStarted=true → 不 start)
            CountDownLatch gate = new CountDownLatch(1);
            f.stateExec.submit(() -> awaitQuiet(gate)); // 占住 stateExecutor:异常清理任务将排队(pending)
            f.capture.listener.onTerminated(1L, "CAPTURE_READ_ERROR_-3"); // 错误终止
            f.runtime.pause();  // 退后台(取消重试链/取消会话)
            f.runtime.resume(); // 回前台:pending 屏障必须挡住 cap.start()
            Thread.sleep(800);  // 给 lifecycleExecutor 的 resume 任务留执行窗口
            assertEquals("异常清理未完成时 pause→resume 不得启动采音", 0, f.capture.startCount);

            gate.countDown(); // 放行清理 → 成功回调 → retryCapture(500ms) → 重启
            awaitStart(f.capture, 1, 5000);
        }
    }

    @Test
    public void cleanupSuccess_restartsCaptureExactlyOnce() throws Exception {
        try (Fixture f = new Fixture()) {
            f.runtime.resume(); // 前台
            f.capture.listener.onTerminated(1L, "CAPTURE_READ_ERROR_-3");
            awaitStart(f.capture, 1, 5000); // 清理成功回调 → 500ms 退避 → 重启(sid=2)
            Thread.sleep(1500); // 再等一个重试周期,确认不二次重启(captureStarted=true 屏障)
            assertEquals("清理成功后采音应恰好重启一次", 1, f.capture.startCount);
        }
    }

    @Test
    public void cleanupFails_noAutoRetry_andResumeBlocked() throws Exception {
        try (Fixture f = new Fixture()) {
            f.asr.failOnStop = true; // 清理路径 invalidateAndStopAsr → asrPort.stop() 抛 → 回调 success=false
            f.runtime.resume();
            f.capture.listener.onTerminated(1L, "CAPTURE_PIPELINE_ERROR");
            assertTrue("清理失败应上报恢复失败状态",
                    awaitStatus(f.statuses, "语音恢复失败", 3000));
            Thread.sleep(1500); // 超过首次重试延迟(500ms)
            assertEquals("清理失败后不得自动重试", 0, f.capture.startCount);
            f.runtime.resume(); // 前台重试:failed 屏障必须挡住
            Thread.sleep(800);
            assertEquals("清理失败后 resume 不得绕过屏障启动", 0, f.capture.startCount);
        }
    }

    @Test
    public void busyRetry_bounded_exhaustsAfterThreeAttempts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capture.busyStarts = 10; // 一直 busy
            f.runtime.resume(); // 前台
            f.capture.listener.onTerminated(1L, "STOPPED"); // 正常停止 → 立即排重试链
            assertTrue("busy 重试 3 次耗尽应报失败状态",
                    awaitStatus(f.statuses, "采音失败,请重进语音页面重试", 8000));
            assertEquals("退避重试应有界(3 次)", 3, f.capture.startCount);
        }
    }

    @Test
    public void busyRetry_cancelledByPause_noFurtherAttempts() throws Exception {
        try (Fixture f = new Fixture()) {
            f.capture.busyStarts = 10;
            f.runtime.resume();
            f.capture.listener.onTerminated(1L, "STOPPED"); // 排首次重试(+500ms)
            Thread.sleep(150);
            f.runtime.pause(); // 取消 captureRetryFuture + 后台屏障
            Thread.sleep(2000);
            assertEquals("pause 应取消 busy 重试链", 0, f.capture.startCount);
        }
    }

    // ---- 工厂正式路径(prepare→create→发布)接线 ----

    @Test
    public void factoryPath_start_preparesCreatesAndPublishes_resumeStartsCapture() throws Exception {
        // 批 A 装配缝:start() → 工厂 prepare(进度上浮)→ create(经 ctx 注入 runner/executors)
        // → 锁内发布 + controller.start;退后台时不开麦,resume 补采音(既有语义保持)
        FakeWakePort wake = new FakeWakePort();
        FakeAsrPort asr = new FakeAsrPort();
        FakeCapture capture = new FakeCapture();
        List<String> statuses = new CopyOnWriteArrayList<>();
        ExecutorService stateExec = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "factory-path-timeout");
            t.setDaemon(true);
            return t;
        });
        final VoiceAssemblyFactory[] holder = new VoiceAssemblyFactory[1];
        holder[0] = new VoiceAssemblyFactory() {
            boolean prepared;
            @Override public void prepare(ProgressListener progress, java.util.function.BooleanSupplier cancelled) {
                prepared = true;
                progress.onProgress("下载英文唤醒模型(~40MB)…"); // 进度经 Runtime.postStatus 上浮
            }
            @Override public VoiceAssembly create(AssemblyContext ctx) {
                VoiceSessionController controller = new VoiceSessionController(
                        wake, asr, new FakeTtsPort(), new FakeVadPort(), new FakeFocusPort(),
                        req -> new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L),
                        new ResponsePresenter(), VoicePolicyConfig.defaults(),
                        ctx.stateExecutor(), ctx.agentExecutor(), ctx.timeoutScheduler(),
                        NoopVoiceMetrics.INSTANCE);
                return new FakeAssembly(controller, capture);
            }
        };
        VoiceRuntime runtime = new VoiceRuntime(new Application() { }, dummyRepository(), holder[0]);
        try {
            runtime.resume(); // 先回前台(否则装配挂 pendingBuild)
            runtime.start(statuses::add, null, null);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && capture.listener == null) Thread.sleep(20);
            assertTrue("工厂 create 后应发布采音(终止监听已注册)", capture.listener != null);
            assertTrue("controller.start 应已执行(KWS 布防)", wake.started);
            assertTrue("prepare 的进度文案应经 StatusListener 上浮",
                    statuses.stream().anyMatch(s -> s.contains("下载英文唤醒模型")));
            awaitStart(capture, 1, 5000); // 前台发布即开麦(sid=2)
        } finally {
            runtime.shutdown();
            stateExec.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    // ---- follow-up4 ①⑤:回调成对/监听与启动解耦(工厂路径) ----

    @Test
    public void followup4_uiStartDuringBuilding_doesNotClearOwnerFailureCallback() throws Exception {
        // follow-up4 ①(follow-up3 引入回归的回归测试):Owner 语义 start 带 onFailure 提交后,
        // UI 的页面级 start(null,null) 不得清掉它——prepare 失败必须仍触发 Owner 失败复位
        try (FactoryPathFixture f = new FactoryPathFixture()) {
            f.prepareFailure = new IllegalStateException("model corrupt");
            AtomicInteger failures = new AtomicInteger();
            f.runtime.resume(); // 前台(装配不挂 pendingBuild)
            f.runtime.start(f.statuses::add, null, failures::incrementAndGet); // Owner 语义启动
            f.runtime.start(f.statuses::add, null, null); // UI 语义(null 不覆盖回调)
            f.openGate();
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && failures.get() == 0) Thread.sleep(20);
            assertEquals("UI 的 start 不得清 Owner 的失败回调", 1, failures.get());
            assertTrue("失败文案应上浮",
                    f.statuses.stream().anyMatch(s -> s.startsWith("启动失败")));
        }
    }

    @Test
    public void followup4_lateOnReady_afterBuilt_firesSynchronously() throws Exception {
        // built 分支:后到的 start 携带 onReady 时同步补发一次(follow-up3 ② 语义保持)
        try (FactoryPathFixture f = new FactoryPathFixture()) {
            f.runtime.resume();
            f.runtime.start(f.statuses::add, null, null);
            f.openGate();
            awaitPublished(f.capture);
            AtomicInteger ready = new AtomicInteger();
            f.runtime.start(f.statuses::add, ready::incrementAndGet, null);
            assertEquals("已 built 时晚传入的 onReady 应同步补发", 1, ready.get());
        }
    }

    @Test
    public void followup4_uiListener_attachBeforePublish_boundOnPublish() throws Exception {
        // ⑤:UI attach 早于装配发布——暂存字段,发布时绑定;无 UI 的启动不得覆盖/清空订阅
        try (FactoryPathFixture f = new FactoryPathFixture()) {
            f.runtime.setUiListener(f.uiListener);
            f.runtime.resume();
            f.runtime.start(f.statuses::add, null, null); // Owner 无 UI 启动(BUILDING,gate 卡住)
            f.openGate();
            awaitPublished(f.capture);
            f.runtime.manualWake("SYSTEM_VIS");
            assertTrue("发布后早 attach 的 UI 监听应收到会话状态",
                    awaitNonIdleState(f.uiStates, 3000));
        }
    }

    @Test
    public void followup4_uiListener_attachAfterPublish_forwardedImmediately() throws Exception {
        // ⑤:发布后 attach——直接转发 controller,不经重新启动
        try (FactoryPathFixture f = new FactoryPathFixture()) {
            f.runtime.resume();
            f.runtime.start(f.statuses::add, null, null);
            f.openGate();
            awaitPublished(f.capture);
            f.runtime.setUiListener(f.uiListener);
            f.runtime.manualWake("SYSTEM_VIS");
            assertTrue("发布后 attach 的监听应立即生效", awaitNonIdleState(f.uiStates, 3000));
        }
    }

    /** 工厂路径脚手架(follow-up4 ⑤/①):prepare 可 gate(阻塞/注入失败),UI 监听收集状态。 */
    private static final class FactoryPathFixture implements AutoCloseable {
        final FakeWakePort wake = new FakeWakePort();
        final FakeAsrPort asr = new FakeAsrPort();
        final FakeCapture capture = new FakeCapture();
        final List<String> statuses = new CopyOnWriteArrayList<>();
        final CountDownLatch prepareGate = new CountDownLatch(1);
        volatile RuntimeException prepareFailure; // 非 null:gate 放行后 prepare 抛
        final List<VoiceSessionState.State> uiStates = new CopyOnWriteArrayList<>();
        final VoiceSessionListener uiListener = new VoiceSessionListener() {
            @Override public void onPartial(String text) { }
            @Override public void onStateChanged(VoiceSessionState.State state) { uiStates.add(state); }
            @Override public void onError(String code) { }
        };
        final ExecutorService stateExec = Executors.newSingleThreadExecutor();
        final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "factory-path-timeout");
            t.setDaemon(true);
            return t;
        });
        final VoiceRuntime runtime;

        FactoryPathFixture() {
            VoiceAssemblyFactory factory = new VoiceAssemblyFactory() {
                @Override public void prepare(ProgressListener progress, java.util.function.BooleanSupplier cancelled) throws Exception {
                    prepareGate.await();
                    if (prepareFailure != null) throw prepareFailure;
                }
                @Override public VoiceAssembly create(AssemblyContext ctx) {
                    VoiceSessionController controller = new VoiceSessionController(
                            wake, asr, new FakeTtsPort(), new FakeVadPort(), new FakeFocusPort(),
                            req -> new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L),
                            new ResponsePresenter(), VoicePolicyConfig.defaults(),
                            ctx.stateExecutor(), ctx.agentExecutor(), ctx.timeoutScheduler(),
                            NoopVoiceMetrics.INSTANCE);
                    return new FakeAssembly(controller, capture);
                }
            };
            runtime = new VoiceRuntime(new Application() { }, dummyRepository(), factory);
        }

        void openGate() { prepareGate.countDown(); }

        @Override public void close() {
            runtime.shutdown();
            stateExec.shutdown();
            try {
                if (!stateExec.awaitTermination(2, TimeUnit.SECONDS)) {
                    stateExec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stateExec.shutdownNow();
            }
            scheduler.shutdownNow();
        }
    }

    private static void awaitPublished(FakeCapture capture) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && capture.listener == null) Thread.sleep(20);
        assertTrue("工厂 create 后应发布采音(终止监听已注册)", capture.listener != null);
    }

    private static boolean awaitNonIdleState(List<VoiceSessionState.State> states, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (states.stream().anyMatch(s -> s != VoiceSessionState.State.IDLE)) return true;
            Thread.sleep(20);
        }
        return states.stream().anyMatch(s -> s != VoiceSessionState.State.IDLE);
    }

    private static final class FakeAssembly implements VoiceAssembly {
        private final VoiceSessionController controller;
        private final VoiceCapturePort capture;
        FakeAssembly(VoiceSessionController controller, VoiceCapturePort capture) {
            this.controller = controller;
            this.capture = capture;
        }
        @Override public VoiceSessionController controller() { return controller; }
        @Override public VoiceCapturePort capture() { return capture; }
        @Override public void close() { controller.shutdown(null); }
    }

    // ---- harness ----

    /** 端到端夹具:真 Runtime(Application stub)+ 真 Controller(假端口)+ 假 capture。 */
    private static final class Fixture implements AutoCloseable {
        final VoiceRuntime runtime;
        final FakeWakePort wake = new FakeWakePort();
        final FakeAsrPort asr = new FakeAsrPort();
        final FakeTtsPort tts = new FakeTtsPort();
        final FakeCapture capture = new FakeCapture();
        final ExecutorService stateExec = Executors.newSingleThreadExecutor();
        /** 夹具自建的 Controller watchdog 调度器(close 时随夹具回收,不泄漏线程)。 */
        final ScheduledExecutorService controllerScheduler =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "runtime-test-timeout");
                    t.setDaemon(true);
                    return t;
                });
        final List<String> statuses = new CopyOnWriteArrayList<>();

        Fixture() {
            runtime = new VoiceRuntime(new Application() { }, dummyRepository(), unusedFactory());
            VoiceSessionController controller = new VoiceSessionController(
                    wake, asr, tts, new FakeVadPort(), new FakeFocusPort(),
                    req -> new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L),
                    new ResponsePresenter(), VoicePolicyConfig.defaults(),
                    stateExec, Runnable::run, controllerScheduler, NoopVoiceMetrics.INSTANCE);
            runtime.publishForTest(controller, capture, statuses::add);
        }

        @Override public void close() {
            runtime.shutdown(); // 停 controller/capture(其收尾任务已入 stateExec 队列)
            // stateExec 传给了 Controller,runtime.shutdown 只关自己的 executor——由夹具回收:
            // graceful shutdown 排干队列(含 controller 收尾),超时强停
            stateExec.shutdown();
            try {
                if (!stateExec.awaitTermination(2, TimeUnit.SECONDS)) {
                    stateExec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stateExec.shutdownNow();
            }
            controllerScheduler.shutdownNow();
        }
    }

    /** publishForTest 路径不触达工厂,仅满足构造非空校验。 */
    private static VoiceAssemblyFactory unusedFactory() {
        return new VoiceAssemblyFactory() {
            @Override public void prepare(ProgressListener progress, java.util.function.BooleanSupplier cancelled) { }
            @Override public VoiceAssembly create(AssemblyContext context) { return null; }
        };
    }

    /** repository 仅满足构造非空校验,恢复路径不触达(engine/gateway 仅占位,runner 不被调用)。 */
    private static AgentRuntimeRepository dummyRepository() {
        return new AgentRuntimeRepository(gw -> null, null, null,
                req -> com.matrix.agent.contract.ModelTurn.directAnswer("done"),
                "runtime-recovery-test", null,
                new TaskScheduler(1, new SessionLockManager()), new MockVehicleStateSource(),
                CapabilityRegistry.createDemoRegistry(),
                KeywordIntentClassifier.INSTANCE, NoopAuditRepository.INSTANCE);
    }

    private static void awaitQuiet(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException ignored) { }
    }

    private static void awaitStart(FakeCapture capture, int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && capture.startCount < expected) {
            Thread.sleep(50);
        }
        assertTrue("等待 capture.start() 达 " + expected + " 次超时(实际 " + capture.startCount + ")",
                capture.startCount >= expected);
    }

    private static boolean awaitStatus(List<String> statuses, String prefix, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (String s : statuses) {
                if (s.startsWith(prefix)) return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    // ---- fakes(自包含,不与 VoiceSessionControllerTest 耦合) ----

    static final class FakeWakePort implements WakeWordPort {
        WakeWordPort.Listener listener;
        volatile boolean started;
        long epoch;
        private long epochSeq;
        @Override public void setListener(WakeWordPort.Listener listener) { this.listener = listener; }
        @Override public WakeStartResult start() { epoch = ++epochSeq; started = true; return new WakeStartResult(epoch, null); }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void stop() { epoch = ++epochSeq; }
    }

    static final class FakeAsrPort implements AsrPort {
        AsrPort.Listener listener;
        boolean failOnStop;
        long sid;
        @Override public void setListener(AsrPort.Listener listener) { this.listener = listener; }
        @Override public AsrStartResult start() { return new AsrStartResult(++sid, null); }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void stop() { if (failOnStop) throw new IllegalStateException("close failed"); }
        @Override public void finish() { }
    }

    static final class FakeTtsPort implements TtsPort {
        @Override public void setListener(TtsPort.Listener listener) {
            if (listener != null) listener.onReady();
        }
        @Override public void speak(com.matrix.agent.voice.SpeakableResponse response, String utteranceId) { }
        @Override public void stop() { }
    }

    static final class FakeVadPort implements VadPort {
        @Override public void setListener(VadPort.Listener listener) { }
        @Override public void feed(byte[] pcm, int len) { }
        @Override public void reset() { }
    }

    static final class FakeFocusPort implements AudioFocusPort {
        @Override public void setListener(AudioFocusPort.Listener listener) { }
        @Override public boolean request() { return true; }
        @Override public void release() { }
    }

    /** 假采音:start 计数、可控 busy、终终权经测试线程手动触发。 */
    static final class FakeCapture implements VoiceCapturePort {
        int startCount;
        int busyStarts; // >0 时前 N 次 start 返回 -1(模拟 AudioRecord 短暂占用)
        long nextSid = 2; // publishForTest 固定 captureSessionId=1,重试从 2 起
        volatile VoiceCapturePort.TerminationListener listener;
        @Override public long start() {
            startCount++;
            return startCount <= busyStarts ? -1 : nextSid++;
        }
        @Override public void stop() { }
        @Override public void setTerminationListener(VoiceCapturePort.TerminationListener l) { listener = l; }
    }
}
