package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;

import com.matrix.agent.api.agent.AgentOperationResult;
import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.AgentServiceInfo;
import com.matrix.agent.api.agent.AgentTaskHandle;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.agent.ConfirmationDecision;
import com.matrix.agent.api.agent.IAgentTaskCallback;
import com.matrix.agent.api.agent.IMatrixAgentManager;
import com.matrix.agent.api.agent.SteerRequest;
import com.matrix.agent.api.common.MatrixErrorCode;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 任务域 Manager：任务提交、快照读取、受控订阅与四个控制操作。
 *
 * <p>实例跨断线稳定：断线时内部 proxy 失效，方法返回与 {@code getState()} 一致的
 * 不可用结果（不抛 NPE、不伪造任务终态）；重连后自动换绑并按原 afterSequence
 * 重注册活跃订阅。订阅返回 AutoCloseable，close() 显式退订。
 */
public final class MatrixAgentManager extends MatrixManagerBase {

    private volatile IMatrixAgentManager service;
    private final List<AgentTaskSubscription> activeSubscriptions = new CopyOnWriteArrayList<>();

    MatrixAgentManager(MatrixAgent matrixAgent, IBinder serviceBinder) {
        super(matrixAgent, serviceBinder);
        service = IMatrixAgentManager.Stub.asInterface(serviceBinder);
    }

    public AgentServiceInfo getServiceInfo() {
        IMatrixAgentManager s = service;
        if (s == null) {
            return null;
        }
        try {
            return s.getServiceInfo();
        } catch (RemoteException e) {
            return handleRemoteException(e, null);
        }
    }

    /** listener 重载：SDK 桥接 AIDL 回调并在门面事件 Handler 上派发（常规调用方入口）。 */
    public AgentTaskHandle submit(AgentRequest request, AgentTaskListener listener) {
        Objects.requireNonNull(listener, "listener");
        return submit(request, new IAgentTaskCallback.Stub() {
            @Override
            public void onTaskEvent(com.matrix.agent.api.agent.AgentTaskEvent event) {
                eventHandler().post(() -> listener.onTaskEvent(event));
            }
        });
    }

    /**
     * 低层扩展接口：直接暴露 AIDL callback（要求调用方自行处理 RemoteException 语义）。
     * 常规调用方使用 {@link #submit(AgentRequest, AgentTaskListener)}。
     */
    public AgentTaskHandle submit(AgentRequest request, IAgentTaskCallback callback) {
        IMatrixAgentManager s = service;
        if (s == null) {
            return unavailableHandle();
        }
        try {
            return s.submit(request, callback);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailableHandle());
        }
    }

    public AgentTaskSnapshot getTaskSnapshot(String taskId) {
        IMatrixAgentManager s = service;
        if (s == null) {
            return null;
        }
        try {
            return s.getTaskSnapshot(taskId);
        } catch (RemoteException e) {
            return handleRemoteException(e, null);
        }
    }

    /** 订阅后必须持有返回句柄并在离开页面/销毁时 close()，否则服务端会持续回调。 */
    public AutoCloseable subscribeTask(String taskId, long afterSequence,
            AgentTaskListener listener) {
        Objects.requireNonNull(listener, "listener");
        AgentTaskSubscription subscription = new AgentTaskSubscription(this, taskId,
                afterSequence, listener);
        // Register before the remote call. If its Binder dies between reading service and
        // subscribeTask(), handleRemoteException starts reconnect and this same object remains
        // available for rebind instead of silently degrading into a no-op handle.
        activeSubscriptions.add(subscription);
        IMatrixAgentManager s = service;
        if (s == null) {
            return subscription;
        }
        try {
            s.subscribeTask(taskId, afterSequence, subscription.bridge);
        } catch (RemoteException e) {
            handleRemoteException(e);
            return subscription;
        }
        return subscription;
    }

    public AgentOperationResult cancelTask(String taskId, String clientOperationId) {
        IMatrixAgentManager s = service;
        if (s == null) {
            return unavailable(clientOperationId);
        }
        try {
            return s.cancelTask(taskId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId));
        }
    }

    public AgentOperationResult steerTask(String taskId, SteerRequest request,
            String clientOperationId) {
        IMatrixAgentManager s = service;
        if (s == null) {
            return unavailable(clientOperationId);
        }
        try {
            return s.steerTask(taskId, request, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId));
        }
    }

    public AgentOperationResult respondConfirmation(String taskId,
            ConfirmationDecision decision, String clientOperationId) {
        IMatrixAgentManager s = service;
        if (s == null) {
            return unavailable(clientOperationId);
        }
        try {
            return s.respondConfirmation(taskId, decision, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId));
        }
    }

    public AgentOperationResult resumeTask(String taskId, String clientOperationId) {
        IMatrixAgentManager s = service;
        if (s == null) {
            return unavailable(clientOperationId);
        }
        try {
            return s.resumeTask(taskId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId));
        }
    }

    /** 断线期控制操作的稳定不可用结果：不改变任何任务状态。 */
    private static AgentOperationResult unavailable(String clientOperationId) {
        return new AgentOperationResult(MatrixErrorCode.SERVICE_NOT_READY, clientOperationId,
                0L, com.matrix.agent.api.common.AgentTaskState.EXECUTION_UNKNOWN);
    }

    private static AgentTaskHandle unavailableHandle() {
        return new AgentTaskHandle(com.matrix.agent.api.common.ParcelSchema.CURRENT, null, 0L,
                com.matrix.agent.api.common.AgentTaskState.REJECTED,
                MatrixErrorCode.SERVICE_NOT_READY);
    }

    @Override
    protected void onMatrixServiceDisconnected() {
        service = null;
    }

    @Override
    protected void onMatrixServiceConnected(IBinder serviceBinder) {
        IMatrixAgentManager reconnected = IMatrixAgentManager.Stub.asInterface(serviceBinder);
        service = reconnected;
        // 重连后重注册活跃订阅；事件缺失段由客户端按 lastSequence 回读 snapshot 补齐。
        for (AgentTaskSubscription subscription : activeSubscriptions) {
            subscription.rebind(reconnected);
        }
    }

    void unsubscribe(AgentTaskSubscription subscription) {
        activeSubscriptions.remove(subscription);
        IMatrixAgentManager current = service;
        if (current == null) {
            return;
        }
        try {
            current.unsubscribeTask(subscription.taskId, subscription.bridge);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    /** AutoCloseable 订阅句柄：close() 显式退订；重复 close 幂等。 */
    private static final class AgentTaskSubscription implements AutoCloseable {
        private final MatrixAgentManager manager;
        private final String taskId;
        private final AtomicLong afterSequence;
        private final IAgentTaskCallback bridge;
        private final AgentTaskListener listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private AgentTaskSubscription(MatrixAgentManager manager, String taskId,
                long afterSequence, AgentTaskListener listener) {
            this.manager = manager;
            this.taskId = taskId;
            this.afterSequence = new AtomicLong(Math.max(0L, afterSequence));
            this.listener = listener;
            this.bridge = new IAgentTaskCallback.Stub() {
                @Override public void onTaskEvent(com.matrix.agent.api.agent.AgentTaskEvent event) {
                    if (event != null) {
                        AgentTaskSubscription.this.afterSequence.accumulateAndGet(event.sequence,
                                Math::max);
                    }
                    manager.eventHandler().post(() -> {
                        if (!closed.get()) listener.onTaskEvent(event);
                    });
                }
            };
        }

        void rebind(IMatrixAgentManager reconnectedService) {
            if (closed.get()) {
                return;
            }
            try {
                reconnectedService.subscribeTask(taskId, afterSequence.get(), bridge);
            } catch (RemoteException e) {
                manager.handleRemoteException(e);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) manager.unsubscribe(this);
        }
    }
}
