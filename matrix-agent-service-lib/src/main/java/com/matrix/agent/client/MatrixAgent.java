package com.matrix.agent.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.matrix.agent.api.agent.AgentServiceInfo;
import com.matrix.agent.api.agent.IMatrixAgentManager;
import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.api.common.MatrixServiceConstants;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连接门面：只负责连接、版本协商、死亡恢复与 Manager 缓存，不承载业务。
 *
 * <p>连接双通道：可选的 OEM {@link ServiceDiscovery} 查询（实现位于 SDK 之外），拿不到再显式
 * bindService 拉起宿主。普通 APK 默认只走显式绑定，不反射 hidden API。Binder death 后 Manager 保持稳定实例并换绑内部 proxy
 * （不清空缓存），订阅按原 afterSequence 自动重注册；重连有界
 * （{@link #RECONNECT_INTERVAL_MS} × {@link #RECONNECT_MAX_ATTEMPTS}）。
 * Manager 只经 {@code getMatrixManager} / 四个类型安全入口获取，内部缓存复用。
 */
public final class MatrixAgent {

    private static final String TAG = "MatrixAgent";

    /** 本 SDK 支持的契约大版本；与服务端 major 不同即协商失败。 */
    public static final int SUPPORTED_CONTRACT_MAJOR = 1;
    /** 可选 OEM discovery 轮询间隔与单次创建的默认等待上限。 */
    static final long POLL_INTERVAL_MS = 50;
    static final long DEFAULT_WAIT_TIMEOUT_MS = 5_000;
    static final long RECONNECT_INTERVAL_MS = 2_000;
    static final int RECONNECT_MAX_ATTEMPTS = 30;

    private final Context applicationContext;
    private final Handler eventHandler;
    private final long waitTimeoutMs;
    private final ServiceLifecycleListener lifecycleListener;
    private final ServiceDiscovery serviceDiscovery;
    /** SDK-owned lanes; never borrow a client application's main thread or Host workers. */
    private final ExecutorService connectionExecutor = boundedExecutor("matrix-sdk-connect", 1, 1);
    private final ExecutorService credentialPipeExecutor = boundedExecutor("matrix-sdk-credential", 1, 4);

    private final Object lock = new Object();
    private final Map<String, MatrixManagerBase> managerCache = new HashMap<>();
    private final AtomicBoolean released = new AtomicBoolean(false);
    /** 连接到达终态（CONNECTED / SERVICE_NOT_READY / release）时放行 create() 的等待。 */
    private final CountDownLatch terminalStateLatch = new CountDownLatch(1);

    private volatile int state = ConnectionState.DISCONNECTED;
    private volatile IMatrixAgentManager managerService;
    private volatile boolean bindPending;
    /**
     * Exactly one discovery/link/negotiate transaction may run at a time.  A generation
     * invalidates stale work, but by itself does not prevent two callers in the same
     * generation from registering the same DeathRecipient twice.
     */
    private final AtomicBoolean connectionInFlight = new AtomicBoolean(false);
    private final DeathLinkCoordinator<IBinder> deathLinks = new DeathLinkCoordinator<>();
    /** 连接代次：release/死亡时递增，作废旧轮次的异步连接与回调（审计 A-104 串行化）。 */
    private final java.util.concurrent.atomic.AtomicLong connectionGeneration =
            new java.util.concurrent.atomic.AtomicLong();

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            handleBinderDied();
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            // System images expose the root manager through ServiceManager, but a normal
            // application process cannot publish there. The explicit bind is therefore a real
            // transport fallback, not just a process-start hint.
            onServiceBinderObtained(binder, connectionGeneration.get());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            handleBinderDied();
        }
    };

    private MatrixAgent(Context context, Handler handler, long waitTimeoutMs,
            ServiceLifecycleListener listener, ServiceDiscovery serviceDiscovery) {
        this.applicationContext = context.getApplicationContext();
        this.eventHandler = handler != null ? handler : new Handler(Looper.getMainLooper());
        this.waitTimeoutMs = waitTimeoutMs;
        this.lifecycleListener = listener;
        this.serviceDiscovery = serviceDiscovery == null ? ServiceDiscovery.NONE : serviceDiscovery;
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 连接并阻塞等待至多 waitTimeoutMs，返回时连接已达终态或超时；
     * 连接结果以 {@link #getState()} 为准，Manager 获取在非 CONNECTED 时返回 null。
     * 内部会阻塞等待，禁止在主线程调用（否则抛 IllegalStateException）。
     */
    public static MatrixAgent create(Context context, ServiceLifecycleListener listener) {
        return create(context, null, DEFAULT_WAIT_TIMEOUT_MS, listener, ServiceDiscovery.NONE);
    }

    /**
     * 同 {@link #create(Context, ServiceLifecycleListener)}；eventHandler 供 null 时取主线程。
     * 阻塞语义同上，禁止主线程调用。
     */
    public static MatrixAgent create(Context context, Handler eventHandler, long waitTimeoutMs,
            ServiceLifecycleListener listener) {
        return create(context, eventHandler, waitTimeoutMs, listener, ServiceDiscovery.NONE);
    }

    /**
     * OEM integration overload. {@code serviceDiscovery} must be supplied by platform-owned
     * code; the published SDK itself never calls hidden framework APIs. Passing {@code null}
     * is equivalent to the normal explicit-bind-only path.
     */
    public static MatrixAgent create(Context context, Handler eventHandler, long waitTimeoutMs,
            ServiceLifecycleListener listener, ServiceDiscovery serviceDiscovery) {
        if (Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "MatrixAgent.create() 会阻塞等待连接，禁止主线程调用；请用 createAsyncHandle()");
        }
        MatrixAgent agent = new MatrixAgent(context, eventHandler, waitTimeoutMs, listener,
                serviceDiscovery);
        agent.initialize();
        try {
            agent.terminalStateLatch.await(waitTimeoutMs + POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return agent;
    }

    /**
     * 异步开始连接。
     *
     * <p>保留此入口是为了兼容已经编译并发布的 SDK 调用方。它无法把实例交给调用方，新的代码
     * 应使用 {@link #createAsyncHandle(Context, ServiceLifecycleListener)}，并在不再需要时调用
     * {@link #release()}。</p>
     */
    @Deprecated
    public static void createAsync(Context context, ServiceLifecycleListener listener) {
        createAsyncHandle(context, listener);
    }

    /**
     * 异步开始连接并立即返回可管理的实例。
     *
     * <p>实例初始状态为 {@link ConnectionState#CONNECTING}；生命周期监听器仍会收到后续状态
     * 回调。调用方应保存返回值，并在页面或进程组件销毁时显式 {@link #release()}。</p>
     */
    public static MatrixAgent createAsyncHandle(Context context, ServiceLifecycleListener listener) {
        MatrixAgent agent = new MatrixAgent(context, null, DEFAULT_WAIT_TIMEOUT_MS, listener,
                ServiceDiscovery.NONE);
        agent.initialize();
        return agent;
    }

    private void initialize() {
        updateState(ConnectionState.CONNECTING);
        obtainServiceBinderAsync();
    }

    // ------------------------------------------------------------------ 公开 API

    /** Agent 任务域 Manager（字符串入口的类型安全版本）。未连接时返回 null。 */
    public MatrixAgentManager getAgentManager() {
        return (MatrixAgentManager) getMatrixManager(MatrixServiceConstants.MANAGER_SERVICE);
    }

    public ModelManager getModelManager() {
        return (ModelManager) getMatrixManager(MatrixServiceConstants.MODEL_SERVICE);
    }

    public VoiceManager getVoiceManager() {
        return (VoiceManager) getMatrixManager(MatrixServiceConstants.VOICE_SERVICE);
    }

    public DownloadManager getDownloadManager() {
        return (DownloadManager) getMatrixManager(MatrixServiceConstants.DOWNLOAD_SERVICE);
    }

    public com.matrix.agent.client.DebugTraceManager getDebugTraceManager() {
        return (com.matrix.agent.client.DebugTraceManager) getMatrixManager(
                MatrixServiceConstants.DEBUG_TRACE_SERVICE);
    }

    public ConversationManager getConversationManager() {
        return (ConversationManager) getMatrixManager(MatrixServiceConstants.CONVERSATION_SERVICE);
    }

    /**
     * 按服务名常量取 Manager（扩展入口；常规业务用四个类型安全方法）。
     * Manager 实例跨断线稳定复用：断线后内部 proxy 置失效，重连自动换绑。
     * 未连接或服务名未知时返回 null。
     */
    public Object getMatrixManager(String serviceName) {
        MatrixManagerBase cached;
        synchronized (lock) {
            cached = managerCache.get(serviceName);
        }
        if (cached != null) {
            return cached;
        }
        IMatrixAgentManager manager = managerService;
        if (manager == null) {
            return null;
        }
        IBinder serviceBinder;
        try {
            serviceBinder = manager.getMatrixService(serviceName);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromMatrixService(e, null);
        }
        if (serviceBinder == null) {
            return null;
        }
        MatrixManagerBase created = createManager(serviceName, serviceBinder);
        if (created == null) {
            return null;
        }
        synchronized (lock) {
            // 并发下可能已有他人创建：保留先入者，保证同一 serviceName 单例。
            MatrixManagerBase raced = managerCache.putIfAbsent(serviceName, created);
            return raced != null ? raced : created;
        }
    }

    public int getState() {
        return state;
    }

    public Context getContext() {
        return applicationContext;
    }

    public Handler eventHandler() {
        return eventHandler;
    }

    public String contextPackageName() {
        return applicationContext.getPackageName();
    }

    /** RemoteException 统一入口：登记断线重连并返回默认值，不向调用方抛 Binder 异常。 */
    public <T> T handleRemoteExceptionFromMatrixService(Throwable e, T defaultValue) {
        Log.w(TAG, "remote call failed: " + e);
        handleBinderDied();
        return defaultValue;
    }

    /** 释放连接与缓存；释放后实例不可复用。已连 Binder 的 death 注册一并解除。 */
    public void release() {
        released.set(true);
        connectionGeneration.incrementAndGet();
        managerService = null;
        unlinkDeathRecipient();
        unbindHost();
        synchronized (lock) {
            managerCache.clear();
        }
        connectionExecutor.shutdownNow();
        credentialPipeExecutor.shutdownNow();
        updateState(ConnectionState.DISCONNECTED);
    }

    // ------------------------------------------------------------------ 连接协议

    private void obtainServiceBinderAsync() {
        if (released.get() || !connectionInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            connectionExecutor.execute(() -> {
            try {
                obtainServiceBinder();
            } finally {
                connectionInFlight.set(false);
            }
            });
        } catch (RejectedExecutionException rejected) {
            connectionInFlight.set(false);
            if (!released.get()) updateState(ConnectionState.SERVICE_NOT_READY);
        }
    }

    /** Runs a bounded secret-pipe writer; false means no reader was left waiting for bytes. */
    boolean executeCredentialPipe(Runnable writer) {
        if (released.get()) return false;
        try {
            credentialPipeExecutor.execute(writer);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    private static ExecutorService boundedExecutor(String name, int threads, int queueCapacity) {
        AtomicInteger sequence = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, name + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    private void obtainServiceBinder() {
        if (released.get()) {
            return;
        }
        final long gen = connectionGeneration.get();
        IBinder binder = findRootService();
        if (released.get() || gen != connectionGeneration.get()) {
            return;
        }
        if (binder == null) {
            // Ordinary APKs have no ServiceManager discovery.  Polling an intentionally empty
            // discovery port for the whole create timeout delays every cold start and, worse,
            // used to release create() as CONNECTING immediately after bindService().  Start the
            // explicit transport now; onServiceConnected is the successful terminal path and the
            // create latch continues waiting for it.  Only a denied/false bind is terminally not
            // ready.
            if (!startHostService()) updateState(ConnectionState.SERVICE_NOT_READY);
            return;
        }
        onServiceBinderObtained(binder, gen);
    }

    private void onServiceBinderObtained(IBinder binder, long gen) {
        if (!deathLinks.reserve(binder, gen, connectionGeneration.get(), released.get())) return;
        try {
            binder.linkToDeath(deathRecipient, 0);
            if (!deathLinks.promote(binder, gen, connectionGeneration.get(), released.get())) {
                unlinkDeathRecipientDirect(binder);
                return;
            }
        } catch (RemoteException e) {
            deathLinks.abandon(binder);
            // link 后立即死亡：走统一死亡恢复
            handleBinderDied();
            return;
        }
        IMatrixAgentManager service = IMatrixAgentManager.Stub.asInterface(binder);
        boolean negotiated;
        try {
            negotiated = negotiate(service);
        } catch (RuntimeException e) {
            unlinkDeathRecipient(binder);
            throw e;
        }
        if (released.get() || gen != connectionGeneration.get()) {
            // 本轮连接已被 release/新轮次作废：释放 death 注册，丢弃结果。
            unlinkDeathRecipient(binder);
            return;
        }
        if (!negotiated) {
            // 协商失败不进入业务调用：释放 death 注册并留在 SERVICE_NOT_READY。
            unlinkDeathRecipient(binder);
            unbindHost();
            managerService = null;
            updateState(ConnectionState.SERVICE_NOT_READY);
            return;
        }
        managerService = service;
        // Keep the explicit fallback binding for the lifetime of this client. Unbinding here
        // would immediately destroy an ordinary app Service and invalidate its Binder.
        if (!isHostBound()) {
            unbindHost();
        }
        synchronized (lock) {
            for (Map.Entry<String, MatrixManagerBase> entry : managerCache.entrySet()) {
                rebindManager(service, entry.getKey(), entry.getValue());
            }
        }
        updateState(ConnectionState.CONNECTED);
    }

    /** 版本协商：major 必须一致；SDK minor 落在服务端支持区间内。 */
    private boolean negotiate(IMatrixAgentManager service) {
        AgentServiceInfo info;
        try {
            info = service.getServiceInfo();
        } catch (RemoteException e) {
            Log.e(TAG, "getServiceInfo failed during negotiate: " + e);
            return false;
        }
        if (info == null) {
            Log.e(TAG, "contract major mismatch: "
                    + "null");
            return false;
        }
        int clientMinor = com.matrix.agent.api.common.ParcelSchema.CURRENT;
        // contractHash 防线（审计 A-105）：major 相同而接口产物不一致的组合必须拒绝。
        // Hash 缺失也是不兼容，不能在生产协议中 fail-open。
        String expected = com.matrix.agent.api.common.ContractVersion.CONTRACT_HASH;
        return ContractNegotiationPolicy.isCompatible(info.contractMajor, info.minClientMinor,
                info.maxClientMinor, info.contractHash, SUPPORTED_CONTRACT_MAJOR, clientMinor,
                expected);
    }

    /**
     * Binder death / 断线：Manager 缓存保持稳定（实例与订阅不丢），仅将各 Manager
     * 内部 proxy 置失效并换绑待重连；宿主 binding 一并释放，重连时按需重建。
     */
    private void handleBinderDied() {
        if (released.get()) {
            return;
        }
        managerService = null;
        connectionGeneration.incrementAndGet(); // 作废旧轮次的在途连接
        unbindHost();
        synchronized (lock) {
            for (MatrixManagerBase manager : managerCache.values()) {
                manager.onMatrixServiceDisconnected();
            }
        }
        unlinkDeathRecipient();
        if (state != ConnectionState.DISCONNECTED) {
            updateState(ConnectionState.DISCONNECTED);
        }
        scheduleReconnect();
    }

    private void rebindManager(IMatrixAgentManager service, String serviceName,
            MatrixManagerBase manager) {
        IBinder child = null;
        try {
            child = service.getMatrixService(serviceName);
        } catch (RemoteException e) {
            Log.w(TAG, "rebind getMatrixService failed: " + serviceName);
        }
        if (child != null) {
            manager.onMatrixServiceConnected(child);
        }
    }

    /** 有界重连：每 RECONNECT_INTERVAL_MS 重试一次，至多 RECONNECT_MAX_ATTEMPTS 次；
     *  客户端 release() 或重连成功即终止。 */
    private void scheduleReconnect() {
        eventHandler.postDelayed(new Runnable() {
            private int attempt;

            @Override
            public void run() {
                if (released.get() || managerService != null) {
                    return;
                }
                if (++attempt > RECONNECT_MAX_ATTEMPTS) {
                    Log.e(TAG, "reconnect gave up after " + RECONNECT_MAX_ATTEMPTS + " attempts");
                    return;
                }
                obtainServiceBinderAsync();
                eventHandler.postDelayed(this, RECONNECT_INTERVAL_MS);
            }
        }, RECONNECT_INTERVAL_MS);
    }

    private boolean startHostService() {
        synchronized (lock) {
            if (released.get()) return false;
            if (bindPending) return true;
            bindPending = true;
        }
        // 宿主 Android Service 由 Host 提供（MatrixAgentManagerService）；正常 APK 只走该路径。
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MatrixServiceConstants.HOST_PACKAGE,
                MatrixServiceConstants.HOST_MANAGER_SERVICE_CLASS));
        intent.setAction(MatrixServiceConstants.MATRIX_AGENT_SERVICE);
        try {
            if (applicationContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                return true;
            }
            {
                synchronized (lock) { bindPending = false; }
                Log.w(TAG, "bind host service returned false");
                return false;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "bind host service denied: " + e.getMessage());
            synchronized (lock) { bindPending = false; }
            return false;
        }
    }

    private void unbindHost() {
        synchronized (lock) {
            if (!bindPending) return;
            bindPending = false;
        }
        try {
            applicationContext.unbindService(serviceConnection);
        } catch (IllegalArgumentException ignored) {
            // binding 已被系统回收的正常路径
        }
    }

    private boolean isHostBound() {
        synchronized (lock) {
            return bindPending;
        }
    }

    private void unlinkDeathRecipient() {
        IBinder linked = deathLinks.takeLinked();
        IBinder inFlight = deathLinks.takeInFlight();
        unlinkDeathRecipientDirect(linked);
        if (inFlight != linked) unlinkDeathRecipientDirect(inFlight);
    }

    private void unlinkDeathRecipient(IBinder expected) {
        if (expected == null) return;
        deathLinks.abandon(expected);
        unlinkDeathRecipientDirect(expected);
    }

    private void unlinkDeathRecipientDirect(IBinder expected) {
        if (expected == null) return;
        try {
            expected.unlinkToDeath(deathRecipient, 0);
        } catch (NoSuchElementException ignored) {
            // Binder death or an earlier cleanup already removed it.
        }
    }

    @SuppressWarnings("unchecked")
    private MatrixManagerBase createManager(String serviceName, IBinder serviceBinder) {
        switch (serviceName) {
            case MatrixServiceConstants.MANAGER_SERVICE:
                return new MatrixAgentManager(this, serviceBinder);
            case MatrixServiceConstants.MODEL_SERVICE:
                return new ModelManager(this, serviceBinder);
            case MatrixServiceConstants.VOICE_SERVICE:
                return new VoiceManager(this, serviceBinder);
            case MatrixServiceConstants.DOWNLOAD_SERVICE:
                return new DownloadManager(this, serviceBinder);
            case MatrixServiceConstants.CONVERSATION_SERVICE:
                return new ConversationManager(this, serviceBinder);
            case MatrixServiceConstants.DEBUG_TRACE_SERVICE:
                return new com.matrix.agent.client.DebugTraceManager(this, serviceBinder);
            default:
                Log.w(TAG, "unknown matrix service: " + serviceName);
                return null;
        }
    }

    private IBinder findRootService() {
        try {
            return serviceDiscovery.findService(MatrixServiceConstants.MATRIX_AGENT_SERVICE);
        } catch (RuntimeException e) {
            // OEM provider failures must not prevent the safe explicit-bind fallback.
            Log.w(TAG, "OEM service discovery failed: " + e);
            return null;
        }
    }

    private void updateState(int newState) {
        synchronized (lock) {
            if (state == newState) {
                return;
            }
            state = newState;
        }
        if (newState == ConnectionState.CONNECTED
                || newState == ConnectionState.SERVICE_NOT_READY
                || newState == ConnectionState.DISCONNECTED) {
            terminalStateLatch.countDown();
        }
        if (lifecycleListener != null) {
            eventHandler.post(() -> lifecycleListener.onLifecycleChanged(this, newState));
        }
    }
}
