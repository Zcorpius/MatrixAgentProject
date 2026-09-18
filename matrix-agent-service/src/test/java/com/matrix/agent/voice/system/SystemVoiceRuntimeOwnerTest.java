package com.matrix.agent.voice.system;
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
import com.matrix.agent.voice.VoiceEntryCoordinator;
import com.matrix.agent.voice.VoicePolicyConfig;
import com.matrix.agent.voice.WakeEvent;
import com.matrix.agent.voice.port.WakeWordPort;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.data.audit.NoopAuditRepository;
import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.VoiceRuntimeHolder;
import com.matrix.agent.voice.VoiceSessionController;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** {@link SystemVoiceRuntimeOwner} 状态机回归(follow-up2):冷建派发一次/BUILDING 覆盖槽不绕过/
 * 页面复用单 Runtime/onShutdown 回收后重建。 */
public final class SystemVoiceRuntimeOwnerTest {

    private static final class Ports implements TtsPort, VadPort, AudioFocusPort {
        final FakeWake wake = new FakeWake();
        final FakeAsr asr = new FakeAsr();
        final FakeCapture capture = new FakeCapture();
        @Override public void setListener(TtsPort.Listener l) { }
        @Override public void speak(com.matrix.agent.voice.SpeakableResponse r, String id) { }
        @Override public void stop() { }
        @Override public void setListener(VadPort.Listener l) { }
        @Override public void feed(byte[] p, int n) { }
        @Override public void reset() { }
        @Override public void setListener(AudioFocusPort.Listener l) { }
        @Override public boolean request() { return true; }
        @Override public void release() { }
    }

    private static final class FakeWake implements WakeWordPort {
        volatile boolean wakeStarted;
        @Override public void setListener(WakeWordPort.Listener l) { }
        @Override public WakeStartResult start() { wakeStarted = true; return new WakeStartResult(1, null); }
        @Override public void feed(byte[] p, int n) { }
        @Override public void stop() { }
    }

    private static final class FakeAsr implements AsrPort {
        @Override public void setListener(AsrPort.Listener l) { }
        @Override public AsrStartResult start() { return new AsrStartResult(1, null); }
        @Override public void feed(byte[] p, int n) { }
        @Override public void stop() { }
        @Override public void finish() { }
    }

    private static final class FakeCapture implements VoiceCapturePort {
        volatile boolean failWithSecurity; // 模拟 RECORD_AUDIO 未授权(capture.start 抛 SecurityException)
        @Override public long start() {
            if (failWithSecurity) throw new SecurityException("RECORD_AUDIO denied");
            return 2;
        }
        @Override public void stop() { }
        @Override public void setTerminationListener(TerminationListener l) { }
    }

    private static AgentRuntimeRepository repo() {
        return new AgentRuntimeRepository(gw -> null, null, null,
                req -> com.matrix.agent.contract.ModelTurn.directAnswer("done"),
                "owner-test", null, new TaskScheduler(1, new SessionLockManager()),
                new MockVehicleStateSource(), CapabilityRegistry.createDemoRegistry(),
                KeywordIntentClassifier.INSTANCE, NoopAuditRepository.INSTANCE);
    }

    private static VoiceAssembly assembly(Ports p, VoiceAssemblyFactory.AssemblyContext ctx) {
        VoiceSessionController controller = new VoiceSessionController(
                p.wake, p.asr, p, p, p,
                req -> new AgentOutcome("req", TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(), 0L),
                new ResponsePresenter(), VoicePolicyConfig.defaults(),
                ctx.stateExecutor(), ctx.agentExecutor(), ctx.timeoutScheduler(), NoopVoiceMetrics.INSTANCE);
        return new VoiceAssembly() {
            @Override public VoiceSessionController controller() { return controller; }
            @Override public VoiceCapturePort capture() { return p.capture; }
            @Override public void close() { }
        };
    }

    private static VoiceRuntime newRuntime(Ports p) {
        VoiceAssemblyFactory f = new VoiceAssemblyFactory() {
            @Override public void prepare(ProgressListener pg, java.util.function.BooleanSupplier c) { }
            @Override public VoiceAssembly create(AssemblyContext ctx) { return assembly(p, ctx); }
        };
        return new VoiceRuntime(new Application() { }, repo(), f);
    }

    private static void awaitActive(VoiceRuntime r) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !(r != null && r.isSessionActive())) Thread.sleep(20);
        assertTrue("待处理 wake 应在就绪后派发并进入会话", r != null && r.isSessionActive());
    }

    @Test
    public void coldStart_lazyCreatesRegistersAndDispatchesPendingOnce() throws Exception {
        VoiceRuntimeHolder.set(null);
        Ports p = new Ports();
        AtomicInteger created = new AtomicInteger();
        SystemVoiceRuntimeOwner owner = new SystemVoiceRuntimeOwner(() -> {
            created.incrementAndGet();
            return newRuntime(p);
        });
        owner.onSystemWake(new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS, "e-1", 1000L, null));
        assertNotNull("懒建后应注册 holder", VoiceRuntimeHolder.get());
        awaitActive(VoiceRuntimeHolder.get());
        assertTrue("Controller.start 已执行(KWS 布防)", p.wake.wakeStarted);
        VoiceRuntimeHolder.get().shutdown();
        VoiceRuntimeHolder.set(null);
    }

    @Test
    public void building_secondWake_overwritesSlot_notBypassed() throws Exception {
        // follow-up2 P1:BUILDING 期间的第二个 wake 只覆盖槽,不绕过 Owner 直呼未装配 Runtime
        VoiceRuntimeHolder.set(null);
        Ports p = new Ports();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        SystemVoiceRuntimeOwner owner = new SystemVoiceRuntimeOwner(() -> {
            created.incrementAndGet();
            VoiceAssemblyFactory f = new VoiceAssemblyFactory() {
                @Override public void prepare(ProgressListener pg, java.util.function.BooleanSupplier c)
                        throws InterruptedException { gate.await(); }
                @Override public VoiceAssembly create(AssemblyContext ctx) { return assembly(p, ctx); }
            };
            return new VoiceRuntime(new Application() { }, repo(), f);
        });
        owner.onSystemWake(new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS, "e-1", 1000L, null));
        owner.onSystemWake(new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS, "e-2", 1200L, null));
        assertEquals("BUILDING 期间不重复建", 1, created.get());
        assertFalse("就绪前 Controller 未布防(未绕过直呼)", p.wake.wakeStarted);
        gate.countDown();
        awaitActive(VoiceRuntimeHolder.get()); // 就绪后派发槽内事件(恰好一次)
        VoiceRuntimeHolder.get().shutdown();
        VoiceRuntimeHolder.set(null);
    }

    @Test
    public void getOrCreate_reused_pageAttachesSameRuntime() {
        VoiceRuntimeHolder.set(null);
        Ports p = new Ports();
        AtomicInteger created = new AtomicInteger();
        SystemVoiceRuntimeOwner owner = new SystemVoiceRuntimeOwner(() -> {
            created.incrementAndGet();
            return newRuntime(p);
        });
        VoiceRuntime a = owner.getOrCreate();
        VoiceRuntime b = owner.getOrCreate();
        assertEquals("页面多次附着复用同一 Runtime(单 Owner,无双采音)", a, b);
        assertEquals(1, created.get());
        a.shutdown();
        VoiceRuntimeHolder.set(null);
    }

    @Test
    public void shutdownIfOwned_clearsHolder_andNextWakeRebuilds() throws Exception {
        VoiceRuntimeHolder.set(null);
        Ports p = new Ports();
        AtomicInteger created = new AtomicInteger();
        SystemVoiceRuntimeOwner owner = new SystemVoiceRuntimeOwner(() -> {
            created.incrementAndGet();
            return newRuntime(p);
        });
        owner.onSystemWake(new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS, "e-1", 1000L, null));
        awaitActive(VoiceRuntimeHolder.get());
        owner.shutdownIfOwned(); // VIS.onShutdown/默认助手取消
        assertNull("回收后 holder 清空", VoiceRuntimeHolder.get());
        owner.onSystemWake(new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS, "e-2", 5000L, null));
        assertEquals("下一次系统唤醒重新懒建", 2, created.get());
        awaitActive(VoiceRuntimeHolder.get());
        VoiceRuntimeHolder.get().shutdown();
        VoiceRuntimeHolder.set(null);
    }

    // ---- follow-up4 ①:启动失败复位(prepare/create/权限失败 → 下次 wake 可重建) ----

    /** 失败注入点:首个实例失败,后续实例成功。 */
    private enum FailMode { PREPARE, CREATE, PERMISSION }

    private static VoiceRuntime runtimeWithFailure(Ports p, FailMode mode, boolean failThis) {
        VoiceAssemblyFactory f = new VoiceAssemblyFactory() {
            @Override public void prepare(ProgressListener pg, java.util.function.BooleanSupplier c) {
                if (failThis && mode == FailMode.PREPARE) throw new IllegalStateException("prepare failed");
            }
            @Override public VoiceAssembly create(AssemblyContext ctx) {
                if (failThis && mode == FailMode.CREATE) throw new IllegalStateException("create failed");
                return assembly(p, ctx);
            }
        };
        if (failThis && mode == FailMode.PERMISSION) p.capture.failWithSecurity = true;
        return new VoiceRuntime(new Application() { }, repo(), f);
    }

    /** 成对回调链:start(status, ready, onFailure=onStartupFailed) → 失败触发 → Owner 复位
     * (holder 清空/回 NULL) → 下次系统唤醒重建并可正常派发。 */
    private void startupFailureResetsAndRebuilds(FailMode mode) throws Exception {
        VoiceRuntimeHolder.set(null);
        Ports p = new Ports();
        AtomicInteger created = new AtomicInteger();
        SystemVoiceRuntimeOwner owner = new SystemVoiceRuntimeOwner(
                () -> runtimeWithFailure(p, mode, created.incrementAndGet() == 1));
        owner.onSystemWake(new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS, "e-1", 1000L, null));
        awaitHolderCleared();
        assertEquals("失败实例只建一次", 1, created.get());
        p.capture.failWithSecurity = false; // PERMISSION 变体:重建复用同一 Ports,复位注入标志
        owner.onSystemWake(new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS, "e-2", 5000L, null));
        assertEquals("失败复位后下次唤醒应重建", 2, created.get());
        awaitActive(VoiceRuntimeHolder.get());
        VoiceRuntimeHolder.get().shutdown();
        VoiceRuntimeHolder.set(null);
    }

    @Test public void startupFailure_prepare_resetsAndRebuilds() throws Exception {
        startupFailureResetsAndRebuilds(FailMode.PREPARE);
    }

    @Test public void startupFailure_create_resetsAndRebuilds() throws Exception {
        startupFailureResetsAndRebuilds(FailMode.CREATE);
    }

    @Test public void startupFailure_permission_resetsAndRebuilds() throws Exception {
        startupFailureResetsAndRebuilds(FailMode.PERMISSION);
    }

    private static void awaitHolderCleared() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && VoiceRuntimeHolder.get() != null) Thread.sleep(20);
        assertNull("启动失败后 Owner 应清 holder 并复位(可重试)", VoiceRuntimeHolder.get());
    }
}
