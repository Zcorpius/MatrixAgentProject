package com.matrix.agent.task.durable;

import com.matrix.agent.host.rpc.AgentRequestValidator;
import com.matrix.agent.host.rpc.CallbackRegistry;
import com.matrix.agent.host.rpc.CallerContext;
import com.matrix.agent.task.*;


import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.matrix.agent.api.agent.AgentOperationResult;
import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.AgentTaskEvent;
import com.matrix.agent.api.agent.AgentTaskHandle;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.agent.IAgentTaskCallback;
import com.matrix.agent.api.agent.SteerRequest;
import com.matrix.agent.api.common.AgentTaskState;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.data.db.AgentTaskEntity;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.task.steer.Steer;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.CancellationToken;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Owns durable task transitions and bridges runtime outcomes into the frozen public state model. */
public final class PersistentTaskManager {
    private static final String TAG = "MatrixAgent";

    private final PersistentTaskStore store;
    private final AgentRuntimeRepository runtime;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, CancellationToken> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CallbackRegistry<IAgentTaskCallback>> subscribers =
            new ConcurrentHashMap<>();
    /** Serializes durable replay with live callback delivery, preserving each task event sequence. */
    private final Object eventDeliveryLock = new Object();

    public PersistentTaskManager(@NonNull PersistentTaskStore store, @NonNull AgentRuntimeRepository runtime,
            @NonNull ExecutorService executor) {
        this.store = store;
        this.runtime = runtime;
        this.executor = executor;
    }

    public int recoverAfterProcessDeath() {
        return store.markInterruptedExecutionsUnknown();
    }

    public AgentTaskHandle submit(@NonNull CallerContext caller, @NonNull AgentRequest request,
            @Nullable IAgentTaskCallback callback) throws RemoteException {
        AgentRequestValidator.validateSubmit(request);
        String hash = AgentRequestValidator.digest(request.clientSessionId, request.text,
                Integer.toString(request.inputSource), request.languageTag);
        PersistentTaskStore.CreateResult created = store.createOrReplay(caller, request, hash);
        if (created.conflict) {
            throw new IllegalArgumentException("clientRequestId reused with another request");
        }
        if (callback != null) register(created.task.taskId, callback);
        long acceptedSequence = created.task.lastSequence;
        int acceptedState = created.task.state;
        if (created.newlyCreated) {
            CancellationToken token = new CancellationToken();
            tokens.put(created.task.taskId, token);
            try {
                executor.execute(() -> execute(created.task.taskId, request.text, token));
            } catch (RejectedExecutionException rejected) {
                tokens.remove(created.task.taskId, token);
                AgentTaskEvent event = store.transition(created.task.taskId,
                        AgentTaskState.REJECTED, MatrixErrorCode.OVERLOADED,
                        "系统繁忙，任务未执行");
                publish(event);
                if (event != null) {
                    acceptedSequence = event.sequence;
                    acceptedState = event.state;
                }
            }
        }
        return new AgentTaskHandle(created.task.taskId, acceptedSequence, acceptedState);
    }

    public @Nullable AgentTaskSnapshot snapshot(@NonNull CallerContext caller, @NonNull String taskId) {
        return store.snapshot(taskId, caller.uid);
    }

    public void subscribe(@NonNull CallerContext caller, @NonNull String taskId, long afterSequence,
            @NonNull IAgentTaskCallback callback) throws RemoteException {
        if (store.ownedTask(taskId, caller.uid) == null) {
            throw new SecurityException("task not owned by caller");
        }
        // A publisher between registration and replay used to deliver the same event once live
        // and once from eventsAfter().  Keep registration, the durable cut and delivery under
        // the same lock as publish so clients observe each sequence exactly once and in order.
        synchronized (eventDeliveryLock) {
            register(taskId, callback);
            List<AgentTaskEvent> replay = store.eventsAfter(taskId, caller.uid, afterSequence);
            for (AgentTaskEvent event : replay) {
                try {
                    callback.onTaskEvent(event);
                } catch (RemoteException dead) {
                    unregister(taskId, callback);
                    throw dead;
                }
            }
        }
    }

    public void unsubscribe(@NonNull String taskId, @Nullable IAgentTaskCallback callback) {
        unregister(taskId, callback);
    }

    public AgentOperationResult cancel(@NonNull CallerContext caller, @NonNull String taskId,
            @NonNull String operationId) {
        requireOperationId(operationId);
        AgentTaskEntity task = store.ownedTask(taskId, caller.uid);
        int code = task == null ? MatrixErrorCode.PERMISSION_DENIED
                : (AgentTaskState.isTerminal(task.state) ? MatrixErrorCode.TOO_LATE
                : MatrixErrorCode.SUCCESS);
        AgentOperationResult operation = store.recordOperation(taskId, caller.uid, operationId,
                "cancel", AgentRequestValidator.digest("cancel"), code);
        if (operation.code != MatrixErrorCode.SUCCESS) return operation;
        CancellationToken token = tokens.get(taskId);
        if (token != null) token.cancel();
        return operation;
    }

    public AgentOperationResult steer(@NonNull CallerContext caller, @NonNull String taskId,
            @NonNull String operationId, @NonNull SteerRequest request) {
        AgentTaskEntity task = store.ownedTask(taskId, caller.uid);
        int code = task == null ? MatrixErrorCode.PERMISSION_DENIED
                : (AgentTaskState.isTerminal(task.state) ? MatrixErrorCode.TOO_LATE
                : MatrixErrorCode.SUCCESS);
        AgentOperationResult operation = store.recordOperation(taskId, caller.uid, operationId,
                "steer", AgentRequestValidator.digest(Integer.toString(request.type), request.text), code);
        if (operation.code != MatrixErrorCode.SUCCESS) return operation;
        runtime.offerSteer(task.taskId, request.type == SteerRequest.TYPE_REPROMPT
                ? Steer.reprompt(request.text) : Steer.defer());
        return operation;
    }

    public AgentOperationResult resume(@NonNull CallerContext caller, @NonNull String taskId,
            @NonNull String operationId) {
        requireOperationId(operationId);
        PersistentTaskStore.ResumeResult resumed = store.beginResume(taskId, caller.uid,
                operationId, AgentRequestValidator.digest("resume"));
        if (!resumed.shouldStart) return resumed.operation;
        CancellationToken token = new CancellationToken();
        tokens.put(taskId, token);
        try {
            executor.execute(() -> execute(taskId, resumed.task.requestText, token));
        } catch (RejectedExecutionException rejected) {
            tokens.remove(taskId, token);
            PersistentTaskStore.OperationFailure failure = store.failAcceptedOperation(taskId,
                    operationId, AgentRequestValidator.digest("resume"),
                    MatrixErrorCode.OVERLOADED, "系统繁忙，恢复任务未执行");
            publish(failure.event);
            return failure.operation;
        }
        return resumed.operation;
    }

    /** Reserved ABI operation; no confirmation feature bit is advertised by this Host version. */
    public AgentOperationResult reservedUnsupported(@NonNull CallerContext caller, @NonNull String taskId,
            @NonNull String operationId, @NonNull String type, @NonNull String payloadHash) {
        requireOperationId(operationId);
        return store.recordOperation(taskId, caller.uid, operationId, type, payloadHash,
                MatrixErrorCode.UNSUPPORTED_OPERATION);
    }

    public void cancelAll() {
        for (CancellationToken token : tokens.values()) token.cancel();
        tokens.clear();
        for (CallbackRegistry<IAgentTaskCallback> registry : subscribers.values()) {
            registry.clear();
        }
        subscribers.clear();
    }

    private void execute(String taskId, String text, CancellationToken token) {
        publish(store.transition(taskId, AgentTaskState.RUNNING, MatrixErrorCode.SUCCESS, "任务执行中"));
        try {
            AgentOutcome outcome = runtime.executeForSession(text, Actor.DRIVER, taskId, token);
            int state = PublicTaskStateMapper.fromInternal(outcome.getFinalState());
            String safeText = state == AgentTaskState.COMPLETED ? "任务已完成"
                    : state == AgentTaskState.CANCELLED ? "任务已取消"
                    : state == AgentTaskState.REJECTED ? "任务被系统拒绝"
                    : state == AgentTaskState.EXECUTION_UNKNOWN ? "任务执行结果未知"
                    : "任务未完成";
            publish(store.transition(taskId, state,
                    (state == AgentTaskState.COMPLETED || state == AgentTaskState.CANCELLED)
                            ? MatrixErrorCode.SUCCESS : MatrixErrorCode.TASK_FAILED, safeText));
        } catch (Throwable error) {
            Log.e(TAG, "[TaskHost] runtime task failed taskId=" + taskId,
                    error);
            publish(store.transition(taskId, AgentTaskState.FAILED,
                    MatrixErrorCode.TASK_FAILED, "任务执行失败"));
        } finally {
            tokens.remove(taskId, token);
        }
    }

    private void register(String taskId, IAgentTaskCallback callback) throws RemoteException {
        subscribers.computeIfAbsent(taskId, ignored -> new CallbackRegistry<>()).add(callback);
    }

    private void unregister(String taskId, IAgentTaskCallback callback) {
        CallbackRegistry<IAgentTaskCallback> registry = subscribers.get(taskId);
        if (registry != null) registry.remove(callback);
    }

    private void publish(@Nullable AgentTaskEvent event) {
        if (event == null) return;
        synchronized (eventDeliveryLock) {
            CallbackRegistry<IAgentTaskCallback> registry = subscribers.get(event.taskId);
            if (registry != null) registry.dispatch(callback -> callback.onTaskEvent(event));
        }
    }

    private static void requireOperationId(String operationId) {
        if (!AgentRequestValidator.isLowerUuid(operationId)) {
            throw new IllegalArgumentException("invalid clientOperationId");
        }
    }
}
