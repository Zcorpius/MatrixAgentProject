package com.matrix.agent.voice.system;

import android.app.Application;
import android.util.Log;

import com.matrix.agent.host.MatrixAgentApplication;
import com.matrix.agent.voice.WakeEvent;
import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.VoiceRuntimeHolder;
import com.matrix.agent.voice.vosk.VoskVoiceAssemblyFactory;

import java.util.function.Supplier;

/**
 * 应用级唯一 Runtime 拥有者(批 C follow-up2 P1 重构):页面与系统入口共用同一 Runtime,
 * 消灭双 Runtime 争用 AudioRecord。状态机 NULL→BUILDING→READY:
 * <ul>
 *   <li>{@link #getOrCreate()}:页面附着也走这里——已有(READY/BUILDING)直接复用,页面不再自建;</li>
 *   <li>{@link #onSystemWake(WakeEvent)}:READY 直接派发;BUILDING 覆盖单待处理槽(就绪后派发一次);
 *       NULL 懒建(冷启动);</li>
 *   <li>{@link #shutdownIfOwned()}:VIS.onShutdown/默认助手被取消时回收(holder 注销+Runtime.shutdown+回 NULL)。</li>
 * </ul>
 * 构造注入 {@code Supplier<VoiceRuntime>} 供 JVM 测试(生产为 Vosk 装配)。
 * 全方法 synchronized(系统 binder 线程/主线程页面附着/服务销毁三来源串行)。
 */
public final class SystemVoiceRuntimeOwner {
    private static final String TAG = "MatrixAgent";

    private enum Phase { NULL, BUILDING, READY }

    private final Supplier<VoiceRuntime> runtimeFactory;
    private VoiceRuntime runtime; // 本 owner 创建并持有的唯一实例
    private Phase phase = Phase.NULL;
    /** 单待处理 wake 槽(BUILDING 期间新事件覆盖旧事件)。 */
    private WakeEvent pendingWake;

    SystemVoiceRuntimeOwner(Supplier<VoiceRuntime> runtimeFactory) {
        this.runtimeFactory = runtimeFactory;
    }

    private static volatile SystemVoiceRuntimeOwner shared;

    /** 进程级单例(Service 与语音页必须同一 owner,防双 Runtime)。 */
    public static SystemVoiceRuntimeOwner shared(Application app) {
        SystemVoiceRuntimeOwner o = shared;
        if (o == null) {
            synchronized (SystemVoiceRuntimeOwner.class) {
                o = shared;
                if (o == null) {
                    o = new SystemVoiceRuntimeOwner(() -> productionRuntime(app));
                    shared = o;
                }
            }
        }
        return o;
    }

    private static SystemVoiceRuntimeOwner legacyProduction(Application app) {
        return new SystemVoiceRuntimeOwner(() -> productionRuntime(app));
    }

    /** 生产构造：executor 取自 Host 全局预算（审计 A-113），生命周期归 Registry。 */
    private static VoiceRuntime productionRuntime(Application app) {
        com.matrix.agent.host.AppContainer container =
                ((MatrixAgentApplication) app.getApplicationContext()).getContainer();
        com.matrix.agent.host.MatrixExecutorRegistry registry = container.getExecutorRegistry();
        return new VoiceRuntime(app, container.getAgentRuntimeRepository(),
                new VoskVoiceAssemblyFactory(app, null),
                registry.voiceDownloadExecutor(), registry.voiceStateExecutor(),
                registry.voiceAgentExecutor(), registry.voiceLifecycleExecutor(),
                registry.voiceTimeoutScheduler());
    }

    /**
     * 页面附着入口(P1 follow-up3):只构造不启动——启动(模型下载/装配/开麦)必须由
     * 已确认 RECORD_AUDIO 的 UI 路径(runtime.start)或系统唤醒({@link #onSystemWake})触发,
     * 权限确认前构造不得开麦。
     */
    public synchronized VoiceRuntime getOrCreate() {
        if (runtime == null) {
            runtime = runtimeFactory.get();
            VoiceRuntimeHolder.set(runtime);
        }
        return runtime;
    }

    /** 当前实例,可能为 null(不触发懒建)——页面 detach(onCleared)用:回收后不得因摘监听再新建。 */
    public synchronized VoiceRuntime currentOrNull() {
        return runtime;
    }

    /** 系统唤醒(全部系统事件统一入口,BUILDING 期间不绕过):READY 派发;BUILDING 覆盖槽;NULL 冷建。 */
    synchronized void onSystemWake(WakeEvent event) {
        switch (phase) {
            case READY:
                runtime.onExternalWake(event);
                return;
            case BUILDING:
                Log.i(TAG, "[Voice] 系统唤醒入待处理槽(装配中,新盖旧)");
                pendingWake = event;
                return;
            case NULL:
                Log.i(TAG, "[Voice] 系统唤醒冷启动:懒建 Runtime,事件入槽");
                pendingWake = event;
                build();
                return;
        }
    }

    /** 启动失败(P2 follow-up3):清 holder、释放 Runtime、清 pending、回 NULL(下次唤醒/重试可重建)。 */
    private synchronized void onStartupFailed() {
        Log.w(TAG, "[Voice] Runtime 启动失败,Owner 复位为可重试");
        if (runtime != null) {
            VoiceRuntime r = runtime;
            runtime = null;
            pendingWake = null;
            phase = Phase.NULL;
            VoiceRuntimeHolder.clearIfSame(r);
            r.shutdown();
        }
    }

    /** VIS.onShutdown/默认助手被取消:回收自己创建的实例(页面 ViewModel 不再拥有 shutdown 权)。 */
    public synchronized void shutdownIfOwned() {
        if (runtime == null) return;
        VoiceRuntime r = runtime;
        runtime = null;
        pendingWake = null;
        phase = Phase.NULL;
        VoiceRuntimeHolder.clearIfSame(r);
        r.shutdown();
        Log.i(TAG, "[Voice] 系统 Owner 回收 Runtime");
    }

    private void build() {
        phase = Phase.BUILDING;
        if (runtime == null) {
            runtime = runtimeFactory.get();
            VoiceRuntimeHolder.set(runtime);
        }
        VoiceRuntime created = runtime;
        // follow-up4 ①:ready/failure 成对传入同一启动调用(不再 setOnFailureListener 字段注册——
        // 旧路径被 start() 无条件清空,失败复位失效)。UI 的 start(null,null) 不会覆盖这里的回调。
        created.start(status -> Log.i(TAG, "[Voice] Runtime: " + status), () -> {
            WakeEvent pending;
            synchronized (this) {
                if (runtime != created) return; // 期间被 shutdown/重建,丢弃
                phase = Phase.READY;
                pending = pendingWake;
                pendingWake = null;
            }
            if (pending != null) created.onExternalWake(pending);
        }, this::onStartupFailed);
        created.resume(); // 系统入口无页面,置前台语义(就绪后开麦)
    }
}
