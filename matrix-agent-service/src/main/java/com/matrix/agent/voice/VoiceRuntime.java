package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.matrix.agent.voice.AgentRunner;
import com.matrix.agent.voice.VoiceAssembly;
import com.matrix.agent.voice.VoiceAssemblyFactory;
import com.matrix.agent.voice.port.VoiceCapturePort;
import com.matrix.agent.voice.VoiceSessionListener;
import com.matrix.agent.voice.VoiceSessionState;
import com.matrix.agent.voice.WakeEvent;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.task.AgentInvocation;
import com.matrix.agent.task.identity.InputSource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;

/**
 * 语音运行时(main 源集;引擎无关)。
 *
 * <p>统一持有 voice 生命周期基础设施(5 个 executor / 生命周期锁 / 前后台 / 采音恢复治理),
 * 引擎特定部分(模型下载/端口装配/native 回收)经 {@link VoiceAssemblyFactory} 注入——
 * release 为 Vosk 工厂，系统入口与 JVM 测试注入各自实现。系统级语音入口
 * (阶段 3 OEM/System Wake)可直接持有本对象，不依赖 Fragment。
 *
 * <p><b>生命周期锁</b>:{@link #lifecycleLock} 保护"装配+启动"与"销毁"(shutdown)互斥。
 * 引擎 prepare 在锁外(耗时不持锁);完成后在锁内检查 cleared、发布字段、start。
 *
 * <p><b>采音恢复治理</b>:异常终止经清理完成回调(成败)驱动重试;captureRecoveryPending/Failed
 * 屏障(pause→resume/busy 重试不得绕过);busy 有界退避;SecurityException 区分提示。
 * 故障预算 30s 窗口熔断。全部恢复路径有 JVM 端到端回归(VoiceRuntimeTest)。
 *
 * <p><b>下载取消</b>:每次 {@link #start} 增 generation,prepare 注入
 * {@code () -> cleared || gen!=generation};旧任务/销毁后任务即取消,保留断点。
 */
public final class VoiceRuntime {

    /** 进度/状态文案回调(下载/装配/就绪/错误)。 */
    public interface StatusListener {
        void onStatus(String status);
    }

    private static final String TAG = "MatrixAgent";

    private final Application app;
    private final AgentRuntimeRepository repository;
    private final VoiceAssemblyFactory factory;

    private final ExecutorService downloadExecutor;
    private final ExecutorService stateExecutor;
    private final ExecutorService agentExecutor;
    /** 会话超时 watchdog。 */
    private final ScheduledExecutorService timeoutScheduler;
    /** pause/resume 的 capture.start/stop 串行执行器(单线程 FIFO);主线程只设意图标志 + 提交,
     *  cap.stop 的 join 在此线程,不阻塞主线程(onPause/onResume)。 */
    private final ExecutorService lifecycleExecutor;
    /** true = 自建线程池（仅测试路径）并在 shutdown 时回收；false = 由 Host Registry 提供。 */
    private final boolean ownsExecutors;

    private final Object lifecycleLock = new Object();
    /** 提供给装配工厂的共享基础设施(executor 由本类统一创建/回收)。 */
    private volatile Runnable onFailureCallback;

    private final VoiceAssemblyFactory.AssemblyContext assemblyContext =
            new VoiceAssemblyFactory.AssemblyContext() {
                @Override public Context appContext() { return app.getApplicationContext(); }
                @Override public AgentRunner runner() {
                    return req -> repository.execute(new AgentInvocation(
                            req.transcript().text(), req.actor(), req.audioZoneId(), InputSource.VOICE,
                            req.transcript().languageTag(), req.transcript().confidence(),
                            req.transcript().confidenceAvailable(), req.cancellationToken()));
                }
                @Override public ExecutorService stateExecutor() { return VoiceRuntime.this.stateExecutor; }
                @Override public ExecutorService agentExecutor() { return VoiceRuntime.this.agentExecutor; }
                @Override public ScheduledExecutorService timeoutScheduler() { return VoiceRuntime.this.timeoutScheduler; }
            };
    private volatile boolean cleared;
    private volatile boolean built;
    /** start 进行中(下载/装配),防 Fragment 重建重复 start 递增 generation / 卡"装配中"。 */
    private volatile boolean startInFlight;
    /** 前台活跃标志。退后台 false → 不装配 + pause 取消会话。默认 false,等 onResume 设 true。 */
    private volatile boolean foregroundActive = false;
    /** 模型已就绪但装配时退后台 → 待回前台 resume 重调装配。 */
    private volatile boolean pendingBuild;
    /** capture 是否已 start(装配时退后台则不 start,resume 回前台补)。 */
    private volatile boolean captureStarted;
    /** 当前 capture 会话的 sid(= capture.start 返回值),onCaptureTerminated 校验防旧会话。 */
    private volatile long captureSessionId;
    /** capture 重试延迟任务句柄;成功 start/pause/shutdown 取消,防旧重试链误判 -1 为失败。 */
    private volatile ScheduledFuture<?> captureRetryFuture;
    /** 异常采音死亡时间窗口熔断:30s 内 >MAX_CAPTURE_FAILURE 次停止重试(防 DEAD_OBJECT 等无限循环)。 */
    private volatile int captureFailureBudget;
    private volatile long captureFirstFailureMs;
    private static final int MAX_CAPTURE_FAILURE = 3;
    /** 异常清理进行中屏障:提交采音异常清理前置 true——resume/busy 重试在清理完成前不得启动新采音(P2)。 */
    private volatile boolean captureRecoveryPending;
    /** 异常清理失败(端口 stop/KWS 重建抛异常):端口状态未知,停自动恢复,等用户重进页面重建。 */
    private volatile boolean captureRecoveryFailed;
    /** attach/detach 的 UI 会话监听(follow-up4 ⑤):装配前暂存,发布 controller 时绑定。 */
    private volatile VoiceSessionListener uiListener;
    private volatile long generation;
    private volatile VoiceAssembly assembly;
    private volatile VoiceSessionController controller;
    /** 依赖接口(测试注入假件驱动恢复路径;生产经装配工厂)。 */
    private volatile VoiceCapturePort capture;
    private volatile StatusListener statusListener;
    private volatile Runnable onReadyCallback;

    /** 生产路径：executor 由 Host 的 MatrixExecutorRegistry 提供（审计 A-113 收敛），
     *  shutdown 时不再回收线程池。 */
    public VoiceRuntime(Application app, AgentRuntimeRepository repository,
            VoiceAssemblyFactory factory, ExecutorService downloadExecutor,
            ExecutorService stateExecutor, ExecutorService agentExecutor,
            ExecutorService lifecycleExecutor, ScheduledExecutorService timeoutScheduler) {
        if (app == null) throw new IllegalArgumentException("app 不能为空");
        if (repository == null) throw new IllegalArgumentException("repository 不能为空");
        if (factory == null) throw new IllegalArgumentException("factory 不能为空");
        this.app = app;
        this.repository = repository;
        this.factory = factory;
        this.downloadExecutor = downloadExecutor;
        this.stateExecutor = stateExecutor;
        this.agentExecutor = agentExecutor;
        this.lifecycleExecutor = lifecycleExecutor;
        this.timeoutScheduler = timeoutScheduler;
        this.ownsExecutors = false;
    }

    /** 仅 JVM 测试便利路径：自建 daemon 线程池并在 shutdown 时回收；生产禁止使用。 */
    public VoiceRuntime(Application app, AgentRuntimeRepository repository, VoiceAssemblyFactory factory) {
        if (app == null) throw new IllegalArgumentException("app 不能为空");
        if (repository == null) throw new IllegalArgumentException("repository 不能为空");
        if (factory == null) throw new IllegalArgumentException("factory 不能为空");
        this.app = app;
        this.repository = repository;
        this.factory = factory;
        this.downloadExecutor = Executors.newSingleThreadExecutor(r -> thread(r, "voice-dl"));
        this.stateExecutor = Executors.newSingleThreadExecutor(r -> thread(r, "voice-state"));
        this.agentExecutor = Executors.newSingleThreadExecutor(r -> thread(r, "voice-agent"));
        this.lifecycleExecutor = Executors.newSingleThreadExecutor(r -> thread(r, "voice-lifecycle"));
        this.timeoutScheduler =
                Executors.newSingleThreadScheduledExecutor(r -> thread(r, "voice-timeout"));
        this.ownsExecutors = true;
    }

    private static Thread thread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * 开始:引擎资源就绪(工厂 prepare)→ 装配+启动。{@code statusListener} 收进度文案;
     * {@code onReady}(发布成功后一次)/{@code onFailure}(启动/装配/权限失败)由启动所有者
     * <b>成对传入同一调用</b>(follow-up4 ①:消灭字段级覆盖——follow-up3 曾让 start 清掉
     * Owner 的失败回调,失败复位失效)。null 不覆盖既有回调:UI 的页面级 start(null,null)
     * 不清系统 Owner 的回调,反之亦然(单一所有者=谁传非 null 谁拥有该槽位)。
     * UI 会话状态经 {@link #setUiListener} attach/detach,与启动解耦(follow-up4 ⑤)。
     * 内部 generation/cleared 控制(cleared 后或新 start 作废旧任务)。
     */
    public void start(StatusListener statusListener, Runnable onReady, Runnable onFailure) {
        if (onReady != null) this.onReadyCallback = onReady;
        if (onFailure != null) this.onFailureCallback = onFailure;
        synchronized (lifecycleLock) {
            this.statusListener = statusListener;
            if (cleared) return;
            if (built) {
                // 已就绪:不重复装配,发布就绪(晚传入的 onReady 同步补发一次)。
                postStatus("就绪:说 \"hey matrix\" 或按\"开始\"");
                fireReadyOnce();
                return;
            }
            if (startInFlight) {
                // 下载/装配中:不重复提交(防递增 generation 让旧任务报"已取消")。
                return;
            }
            startInFlight = true;
        }
        final long gen = ++generation; // 首次启动才递增(startInFlight 保证不重复)
        final BooleanSupplier cancelled = () -> cleared || gen != generation;
        downloadExecutor.submit(() -> {
            if (cleared) return;
            try {
                // 引擎资源(模型下载/校验/锁竞争退避)在工厂内,含 LockBusy 有界重试
                factory.prepare(progress -> {
                    if (!cancelled.getAsBoolean()) postStatus(progress);
                }, cancelled);
                if (cancelled.getAsBoolean()) return;
                postStatus("模型就绪,装配中…");
                buildAndStart();
            } catch (Exception e) {
                if (gen != generation || cleared) return; // 旧 generation 取消,静默不报失败
                Log.e(TAG, "[Voice] 启动失败: " + e.getClass().getSimpleName(), e);
                // P3: UI 只输出稳定文案+异常类型,原始 message 可能含模型绝对路径
                postStatus("启动失败(" + e.getClass().getSimpleName() + "),请重试");
                fireFailure();
            } finally {
                synchronized (lifecycleLock) { startInFlight = false; }
            }
        });
    }

    private void buildAndStart() {
        if (cleared || built) return;
        if (!foregroundActive) { // 退后台不装配(防下载完成后台启动麦)
            pendingBuild = true; // 记待装配,resume 回前台时重调本方法
            Log.i(TAG, "[Voice] 退后台,暂停装配,回前台重试");
            postStatus("语音暂停(退后台),回页面重试");
            return;
        }
        pendingBuild = false;
        // 引擎装配在锁外(耗时不持锁);装配后到锁内发布+启动
        final VoiceAssembly built;
        try {
            built = factory.create(assemblyContext);
        } catch (Exception e) {
            Log.e(TAG, "[Voice] 装配失败: " + e.getClass().getSimpleName(), e);
            // P3: UI 只输出异常类型(Vosk/文件系统异常 message 可能含模型绝对路径)
            postStatus("装配失败(" + e.getClass().getSimpleName() + "),请重试");
            fireFailure();
            return;
        }
        synchronized (lifecycleLock) {
            if (cleared || this.built) {
                closeQuietly(built);
                return;
            }
            this.assembly = built;
            this.controller = built.controller();
            this.capture = built.capture();
            this.capture.setTerminationListener(this::onCaptureTerminated); // #5:采音终止→恢复链入口
            this.built = true;
            try {
                VoiceSessionListener ui = this.uiListener; // ⑤:装配期绑定时已 attach 的 UI 订阅
                if (ui != null) controller.setUiListener(ui);
                controller.start();
                // 锁内 capture.start 前复查 foregroundActive——模型加载在锁外,
                // 加载期间可能已 pause,此时不能后台启动麦;built=true 但 captureStarted=false,
                // 由 resume 在回前台时补 capture.start()。
                if (foregroundActive) {
                    long sid = capture.start();
                    if (sid >= 0) {
                        captureStarted = true;
                        captureSessionId = sid;
                    } else {
                        captureStarted = false;
                        // P2: busy(STOPPING 残留/AudioRecord 短暂占用)有界退避重试,
                        // 不再等下一次生命周期事件,页面保持前台也能恢复
                        Log.w(TAG, "[Voice] capture.start 返回 busy,安排退避重试");
                        retryCapture(0);
                    }
                } else {
                    captureStarted = false;
                    Log.i(TAG, "[Voice] 装配完成但已退后台,采音待回前台启动");
                }
            } catch (SecurityException e) {
                // P2: 权限类失败与初始化失败区分——提示用户授予后重进,不盲目重试
                Log.e(TAG, "[Voice] 麦克风权限缺失,无法启动采音", e);
                rollbackPublish(built);
                postStatus("缺少麦克风权限,请在系统设置授予后重进语音页");
                fireFailure();
                return;
            } catch (RuntimeException e) {
                // start 抛(AudioRecord 初始化/设备):回滚 publish,避免 capture 死、controller 活的卡死态
                Log.e(TAG, "[Voice] 启动失败,回滚: " + e.getClass().getSimpleName(), e);
                rollbackPublish(built);
                postStatus("启动采音失败(" + e.getClass().getSimpleName() + "),请重试");
                fireFailure();
                return;
            }
        }
        postStatus("就绪:说 \"hey matrix\" 或按\"开始\"");
        fireReadyOnce();
    }

    public void manualWake() {
        manualWake("manual");
    }

    /** 带唤醒来源(manual/system),贯通到 Controller metrics(替换硬编码 vosk)。 */
    public void manualWake(String source) {
        VoiceSessionController c = controller;
        if (c != null) c.manualWake(source);
    }

    /**
     * 外部唤醒事件入口(批 B:经 VoiceEntryCoordinator 校验/去重/冷却/仲裁后的派发目标)。
     * epoch 旁路语义与 manualWake 一致(系统侧来源无 recognizer 代次);audioZoneId 不路由
     * (固定 DRIVER/默认音区,§6.3-8 由 Controller 会话路径保证)。
     */
    public void onExternalWake(WakeEvent event) {
        manualWake(event.source()); // 来源贯通(系统入口无 recognizer 代次,epoch 旁路)
    }

    public void cancel() {
        VoiceSessionController c = controller;
        if (c != null) c.onCancel();
    }

    /** 页面附着/摘除 UI 监听(attach/detach,与启动解耦,follow-up4 ⑤):controller 未发布时
     * 暂存字段,装配发布时绑定——Owner 无 UI 启动(start 不带监听)不再覆盖/清空已 attach 的 UI 订阅。 */
    public void setUiListener(VoiceSessionListener listener) {
        this.uiListener = listener;
        VoiceSessionController c = controller;
        if (c != null) c.setUiListener(listener);
    }

    /** 就绪回调触发一次后清除(晚订阅同步补发也走这里)。 */
    private void fireReadyOnce() {
        Runnable r = onReadyCallback;
        if (r != null) {
            onReadyCallback = null;
            r.run();
        }
    }

    private void fireFailure() {
        onReadyCallback = null; // 失败即终局:清就绪回调,不再补发
        Runnable f = onFailureCallback;
        if (f != null) f.run();
    }

    /** 外部唤醒闸门用:当前是否存在活跃语音会话(非 IDLE 即活跃)。 */
    public boolean isSessionActive() {
        VoiceSessionController c = controller;
        return c != null && c.currentState() != VoiceSessionState.State.IDLE;
    }

    /** 退后台:停采音释放麦克风。foregroundActive 写与 buildAndStart 互斥(同一 lifecycleLock)。 */
    public void pause() {
        if (cleared) return; // ④:已销毁(Owner 回收后存活的 ViewModel 持旧实例),pause 无意义
        synchronized (lifecycleLock) {
            foregroundActive = false; // 主线程立即标记非前台(buildAndStart 据此不后台开麦)
            captureStarted = false;
            cancelFuture(captureRetryFuture);
            captureRetryFuture = null;
        }
        VoiceSessionController c = controller;
        if (c != null) c.onCancel(); // 取消 THINKING/SPEAKING(锁外,不持 lifecycleLock)
        VoiceCapturePort cap = capture;
        if (cap != null) {
            // cap.stop 内部 join 采音线程(≤1s);投 lifecycle executor 串行执行,不阻塞主线程(onPause)。
            // 与 resume 的 start 同一 executor,单线程 FIFO 保证 pause→resume 时 stop 先于 start。
            executeOnLifecycle(() -> {
                try { cap.stop(); } catch (Exception e) { Log.w(TAG, "[Voice] pause: " + e.getClass().getSimpleName()); }
            });
        }
    }

    /** 回前台:恢复采音。 */
    public void resume() {
        synchronized (lifecycleLock) {
            foregroundActive = true; // 主线程立即标记前台
            if (cleared) return;
            if (!built && pendingBuild) {
                // 模型已就绪但装配时退后台,回前台重调装配。
                Log.i(TAG, "[Voice] 回前台,重试装配");
                downloadExecutor.submit(this::buildAndStart);
                return;
            }
            if (built && !captureStarted && capture != null) {
                final VoiceCapturePort cap = capture;
                // capture.start 投 lifecycle executor:与 pause 的 stop 同一单线程 FIFO,
                // 保证 pause→resume 的 stop 先于 start;主线程不持锁做 AudioRecord 构造。
                // executor 内重获锁复查(pause/shutdown 抢先时 foregroundActive/cleared/capture 已变 → 不开麦)。
                // ④:与 pause 对称——shutdown 并发时拒绝提交不重抛(cleared 短路之外的竞态窗口兜底)。
                executeOnLifecycle(() -> {
                    synchronized (lifecycleLock) {
                        if (capture != cap || !captureStartAllowed(cleared, foregroundActive,
                                captureStarted, captureRecoveryPending, captureRecoveryFailed)) {
                            return; // 含异常清理未完成/失败的恢复屏障(P2)
                        }
                        try {
                            long sid = cap.start();
                            if (sid >= 0) {
                                captureStarted = true;
                                captureSessionId = sid;
                                cancelFuture(captureRetryFuture);
                                captureRetryFuture = null;
                            } else {
                                // P2: busy(STOPPING 残留/AudioRecord 短暂占用)不再只打日志等生命周期——
                                // 有界退避重试,页面保持前台也能恢复采音
                                Log.w(TAG, "[Voice] resume capture.start busy,安排退避重试");
                                retryCapture(0);
                            }
                        } catch (Exception e) {
                            postStatus("恢复采音失败(" + e.getClass().getSimpleName() + "),请重进语音页");
                        }
                    }
                });
            }
        }
    }

    /** ④:shutdown 与 pause/resume 并发时 lifecycleExecutor 可能拒绝提交(存活 ViewModel 持旧实例)——
     * 统一兜底捕获:capture 的停止已由 shutdown 路径负责,不向调用方重抛(cleared 短路之外的竞态窗口)。 */
    private void executeOnLifecycle(Runnable task) {
        try {
            lifecycleExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "[Voice] lifecycle executor 已关闭,跳过生命周期任务");
        }
    }

    /**
     * 采音启动屏障(单一判定,resume/busy 重试/清理回调共用):cleared、后台、已有采音、
     * 异常清理未完成(pending)或已失败(failed)时一律不得启动新采音——防止 pause→resume 或
     * busy 重试绕过恢复流程,在端口清理完成前向旧(可能已损坏)端口喂音频(P2)。独立静态方法供 JVM 测试。
     */
    static boolean captureStartAllowed(boolean cleared, boolean foregroundActive, boolean captureStarted,
            boolean recoveryPending, boolean recoveryFailed) {
        return !cleared && foregroundActive && !captureStarted && !recoveryPending && !recoveryFailed;
    }

    /** #5:采音线程退出(错误或正常 stop)→ 进 lifecycleLock 与 pause/resume 串行,sid 校验防旧会话。
     * P1:采音线程不直连 Controller——错误经 sid 校验通过后才转交,防迟到错误影响 pause→resume 恢复后的新会话。
     * P2:异常路径提交清理前置 captureRecoveryPending=true(resume/重试被屏障);重试只挂清理成功回调,
     * 回调内经 captureStartAllowed 复查;清理失败(failed)取消自动恢复,提示重进页面。 */
    private void onCaptureTerminated(long sid, String reason) {
        synchronized (lifecycleLock) {
            Log.w(TAG, "[Voice] 采音终止: sid=" + sid + " reason=" + reason);
            if (sid != captureSessionId) return; // 旧会话回调,先校验再改状态
            captureStarted = false;
            if ("STOPPED".equals(reason)) {
                if (!captureStartAllowed(cleared, foregroundActive, captureStarted,
                        captureRecoveryPending, captureRecoveryFailed)) return;
                captureFailureBudget = 0; // 正常 stop(pause/超时),前台恢复不算异常死亡
                retryCapture(0);
                return;
            }
            // 异常 termination(精确 CAPTURE_* 码):sid 校验通过才转交 Controller 收敛状态。
            // 故障预算先算(熔断语义不变);清理统一带回调(熔断/后台路径也需清 pending 屏障)。
            VoiceSessionController c = controller;
            if (c == null) return;
            long now = System.currentTimeMillis();
            if (now - captureFirstFailureMs > 30_000) {
                captureFailureBudget = 0; // 窗口过期,重新计数
                captureFirstFailureMs = now;
            }
            final boolean exhausted = ++captureFailureBudget > MAX_CAPTURE_FAILURE;
            if (exhausted) {
                Log.e(TAG, "[Voice] 采音异常连续死亡熔断(30s 内 >" + MAX_CAPTURE_FAILURE + " 次),停止重试");
                postStatus("采音持续失败,请重进语音页面");
            }
            captureRecoveryPending = true;
            c.onCaptureError(reason, success -> {
                synchronized (lifecycleLock) {
                    captureRecoveryPending = false;
                    if (!success) {
                        // P2:清理路径抛异常,端口状态未知——取消自动恢复,等用户重进页面重建
                        captureRecoveryFailed = true;
                        Log.e(TAG, "[Voice] 采音清理失败,停止自动恢复");
                        postStatus("语音恢复失败,请重进语音页面");
                        return;
                    }
                    // 清理完成后再校验:期间可能已 pause/shutdown、被 resume 恢复或熔断,均不再重启
                    if (exhausted || captureSessionId != sid
                            || !captureStartAllowed(cleared, foregroundActive, captureStarted,
                                    captureRecoveryPending, captureRecoveryFailed)) {
                        return;
                    }
                    retryCapture(0);
                }
            });
        }
    }

    private void retryCapture(int attempt) {
        if (attempt >= 3) {
            Log.e(TAG, "[Voice] 采音重试耗尽,标记失败");
            postStatus("采音失败,请重进语音页面重试");
            return;
        }
        // 屏障:已恢复(resume)/后台/销毁/异常清理未完成或失败,静默不排(后台不再误报"采音失败")
        if (!captureStartAllowed(cleared, foregroundActive, captureStarted,
                captureRecoveryPending, captureRecoveryFailed)) return;
        final int next = attempt + 1;
        try {
            // 退避:每次延迟递增,避免同毫秒 3 次一起失败(设备短暂 busy/DEAD_OBJECT 恢复期)。
            captureRetryFuture = timeoutScheduler.schedule(() -> {
                synchronized (lifecycleLock) { doRetryCapture(next); }
            }, 500L * (attempt + 1), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            if (!cleared) Log.w(TAG, "[Voice] 采音重试 scheduler 拒绝");
        }
    }

    private void doRetryCapture(int attempt) {
        VoiceCapturePort c = capture;
        if (c == null) return;
        if (!captureStartAllowed(cleared, foregroundActive, captureStarted,
                captureRecoveryPending, captureRecoveryFailed)) return; // 已恢复(如 resume)/恢复屏障
        try {
            long sid = c.start();
            if (sid >= 0) {
                captureStarted = true;
                captureSessionId = sid;
                cancelFuture(captureRetryFuture);
                captureRetryFuture = null;
                Log.i(TAG, "[Voice] 采音重试成功(第 " + attempt + " 次,sid=" + sid + ")");
            } else {
                retryCapture(attempt);
            }
        } catch (Exception e) {
            Log.w(TAG, "[Voice] 采音重试异常: " + e.getClass().getSimpleName());
            retryCapture(attempt);
        }
    }

    /** 回滚已发布的装配字段并回收装配(启动采音失败/权限缺失),防 capture 死、controller 活的卡死态。 */
    private void rollbackPublish(VoiceAssembly a) {
        this.assembly = null;
        this.controller = null;
        this.capture = null;
        this.built = false;
        this.captureStarted = false;
        this.captureRecoveryPending = false;
        this.captureRecoveryFailed = false;
        closeQuietly(a);
    }

    /**
     * 测试注入(JVM 驱动恢复路径,绕过工厂的 Vosk/Android 装配):等价 buildAndStart 锁内发布段——
     * 发布 controller/capture、置 built/captureStarted、注册终止监听、controller.start()。
     * captureSessionId 固定 1(对齐假 capture 首个 sid)。仅同包测试使用;
     * 工厂正式路径(prepare→create→发布)由 start() 经假工厂覆盖。
     */
    void publishForTest(VoiceSessionController controller, VoiceCapturePort capture,
            StatusListener statusListener) {
        synchronized (lifecycleLock) {
            this.controller = controller;
            this.capture = capture;
            this.statusListener = statusListener;
            this.built = true;
            this.captureStarted = true;
            this.captureSessionId = 1L;
            controller.start();
            capture.setTerminationListener(this::onCaptureTerminated);
        }
    }

    /**
     * 销毁:回收所有 voice 资源。capture 先停(经 lifecycleExecutor 异步,≤1s,不阻塞主线程);
     * 装配回收(assembly.close:Controller shutdown 串行 stop Recognizer → afterStopped 释放
     * tts 引擎 + Model);随后关本类 executor(stateExecutor graceful 排干收尾任务,其余 shutdownNow)。
     */
    public void shutdown() {
        VoiceCapturePort cap;
        VoiceAssembly a;
        synchronized (lifecycleLock) {
            cleared = true;
            cancelFuture(captureRetryFuture);
            captureRetryFuture = null;
            cap = capture;
            a = assembly;
        }
        // capture 先停(停止 feed);cap.stop 内部 join 采音线程(≤1s),投 lifecycleExecutor 异步执行,
        // 不阻塞主线程(onCleared)——与 pause/resume 对齐。末尾 lifecycleExecutor.shutdown()
        // 拒绝新任务,已提交的 cap.stop 继续异步完成。
        if (cap != null) {
            lifecycleExecutor.execute(() -> {
                try {
                    cap.stop();
                } catch (Exception e) {
                    Log.w(TAG, "[Voice] shutdown cap: " + e.getClass().getSimpleName());
                }
            });
        }
        if (a != null) {
            // 装配回收:内部 Controller.shutdown(串行 stop asr/wake/tts)+ afterStopped 释放
            // tts 引擎与 Model(native 顺序保证);收尾任务经 stateExecutor,随下方 graceful shutdown 排干。
            try {
                a.close();
            } catch (Exception e) {
                Log.w(TAG, "[Voice] shutdown assembly: " + e.getClass().getSimpleName());
            }
        }
        if (!ownsExecutors) {
            return; // executor 归 Host Registry，随 Host 生命周期统一回收
        }
        stateExecutor.shutdown(); // graceful:排干 Controller 收尾任务后退出
        agentExecutor.shutdownNow();
        downloadExecutor.shutdownNow();
        timeoutScheduler.shutdownNow();
        lifecycleExecutor.shutdown(); // 等 pending pause/resume 任务完成(它们复查 cleared 后 no-op)
    }

    private void postStatus(String status) {
        StatusListener l = statusListener;
        if (l != null) l.onStatus(status);
    }

    private static void cancelFuture(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    private static void closeQuietly(VoiceAssembly a) {
        if (a == null) return;
        try {
            a.close();
        } catch (Exception e) {
            Log.w(TAG, "[Voice] close assembly: " + e.getClass().getSimpleName());
        }
    }
}
