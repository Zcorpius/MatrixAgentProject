package com.matrix.agent.host;

import android.app.Service;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;

import androidx.annotation.Nullable;

import com.matrix.agent.BuildConfig;
import com.matrix.agent.api.agent.AgentOperationResult;
import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.AgentServiceInfo;
import com.matrix.agent.api.agent.AgentTaskHandle;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.agent.ConfirmationDecision;
import com.matrix.agent.api.agent.IAgentTaskCallback;
import com.matrix.agent.api.agent.IMatrixAgentManager;
import com.matrix.agent.api.agent.SteerRequest;
import com.matrix.agent.api.common.ContractVersion;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.common.AgentTaskState;
import com.matrix.agent.api.common.ParcelSchema;

/**
 * System-UID manager service. It registers its root Binder with ServiceManager and also exposes
 * the signature-protected explicit-bind endpoint used by clients. Binder transactions validate/authorize,
 * read or write short durable records, then hand long work to {@link PersistentTaskManager}.
 */
public final class MatrixAgentManagerService extends Service {
    public static final String ACCESS_PERMISSION = "com.matrix.agent.permission.ACCESS_AGENT";

    private MatrixServiceGraph graph;

    private final IMatrixAgentManager.Stub binder = new IMatrixAgentManager.Stub() {
        @Override
        public AgentServiceInfo getServiceInfo() {
            CallerContext.capture(MatrixAgentManagerService.this).enforceTrusted(
                    MatrixAgentManagerService.this);
            return new AgentServiceInfo(1, ParcelSchema.CURRENT, ParcelSchema.CURRENT,
                    graph == null ? 0 : graph.featureFlags(), BuildConfig.VERSION_NAME,
                    getPackageName(), ContractVersion.CONTRACT_HASH);
        }

        @Override
        public IBinder getMatrixService(String serviceName) {
            CallerContext.capture(MatrixAgentManagerService.this).enforceTrusted(
                    MatrixAgentManagerService.this);
            // The root Binder is the sole discovery and authorization boundary. Domain binders
            // are Host-private facades obtained only after this trusted transaction succeeds.
            if (MatrixServiceConstants.MANAGER_SERVICE.equals(serviceName)) return binder;
            return MatrixServiceConstants.MODEL_SERVICE.equals(serviceName) ? modelServiceBinder()
                    : MatrixServiceConstants.DOWNLOAD_SERVICE.equals(serviceName) ? downloadServiceBinder()
                    : MatrixServiceConstants.VOICE_SERVICE.equals(serviceName) ? voiceServiceBinder()
                    : null;
        }

        @Override
        public AgentTaskHandle submit(AgentRequest request, IAgentTaskCallback callback)
                throws RemoteException {
            CallerContext caller = caller();
            if (!graph.persistenceAvailable() || !graph.tasksAvailable()) {
                return rejectedHandle(graph.persistenceAvailable()
                        ? MatrixErrorCode.SERVICE_NOT_READY : MatrixErrorCode.PERSISTENCE_UNAVAILABLE);
            }
            return taskManager().submit(caller, request, callback);
        }

        @Override
        public AgentTaskSnapshot getTaskSnapshot(String taskId) {
            CallerContext caller = caller();
            String validated = requireTaskId(taskId);
            // Snapshot has no error-code field in the frozen AIDL. A null snapshot is the only
            // truthful non-mutating reply while encrypted persistence/recovery is unavailable.
            return tasksReady() ? taskManager().snapshot(caller, validated) : null;
        }

        @Override
        public void subscribeTask(String taskId, long afterSequence, IAgentTaskCallback callback)
                throws RemoteException {
            if (callback == null) throw new IllegalArgumentException("callback required");
            CallerContext caller = caller();
            String validated = requireTaskId(taskId);
            requireTasksReady();
            taskManager().subscribe(caller, validated, afterSequence, callback);
        }

        @Override
        public void unsubscribeTask(String taskId, IAgentTaskCallback callback) {
            CallerContext caller = caller();
            String validated = requireTaskId(taskId);
            requireTasksReady();
            if (taskManager().snapshot(caller, validated) == null) {
                throw new SecurityException("task not owned by caller");
            }
            taskManager().unsubscribe(validated, callback);
        }

        @Override
        public AgentOperationResult cancelTask(String taskId, String operationId) {
            if (!tasksReady()) return unavailableOperation(operationId, taskAvailabilityError());
            return taskManager().cancel(caller(), requireTaskId(taskId), operationId);
        }

        @Override
        public AgentOperationResult steerTask(String taskId, SteerRequest request,
                String operationId) {
            if (!tasksReady()) return unavailableOperation(operationId, taskAvailabilityError());
            AgentRequestValidator.validateSteer(request);
            return taskManager().steer(caller(), requireTaskId(taskId), operationId, request);
        }

        @Override
        public AgentOperationResult respondConfirmation(String taskId,
                ConfirmationDecision decision, String operationId) {
            if (!tasksReady()) return unavailableOperation(operationId, taskAvailabilityError());
            AgentRequestValidator.validateConfirmation(decision);
            return taskManager().reservedUnsupported(caller(), requireTaskId(taskId), operationId,
                    "confirmation", AgentRequestValidator.digest(decision.confirmationId,
                            Boolean.toString(decision.approve)));
        }

        @Override
        public AgentOperationResult resumeTask(String taskId, String operationId) {
            if (!tasksReady()) return unavailableOperation(operationId, taskAvailabilityError());
            return taskManager().resume(caller(), requireTaskId(taskId), operationId);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        AppContainer container = ((MatrixAgentApplication) getApplication()).getContainer();
        ModelServiceStub.CallerResolver resolver = this::caller;
        graph = new MatrixServiceGraph(container, resolver);
        registerRootBinder();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // The process owns the global ServiceManager entry. Restart it after a recoverable
        // process kill so the boot receiver is not the only registration path.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (graph != null) graph.shutdown();
        super.onDestroy();
    }

    private CallerContext caller() {
        CallerContext caller = CallerContext.capture(this);
        caller.enforceTrusted(this);
        return caller;
    }

    @SuppressLint("PrivateApi")
    private void registerRootBinder() {
        try {
            ServiceManager.addService(MatrixServiceConstants.MATRIX_AGENT_SERVICE, binder);
        } catch (RuntimeException e) {
            // Running without the advertised root service would split the SDK's discovery and
            // explicit-bind paths. Fail startup rather than silently serving a partial system API.
            graph.shutdown();
            throw new IllegalStateException("Unable to register Matrix root Binder", e);
        }
    }

    private PersistentTaskManager taskManager() {
        requireTasksReady();
        return graph.tasks();
    }

    private boolean tasksReady() {
        return graph != null && graph.persistenceAvailable() && graph.tasksAvailable();
    }

    private void requireTasksReady() {
        if (!tasksReady()) {
            throw new IllegalStateException(graph == null ? "service graph unavailable"
                    : graph.persistenceAvailable() ? "persistent task recovery is not complete"
                    : graph.persistenceUnavailableReason());
        }
    }

    private int taskAvailabilityError() {
        return graph != null && graph.persistenceAvailable()
                ? MatrixErrorCode.SERVICE_NOT_READY : MatrixErrorCode.PERSISTENCE_UNAVAILABLE;
    }

    private static AgentTaskHandle rejectedHandle(int errorCode) {
        return new AgentTaskHandle(ParcelSchema.CURRENT, null, 0L, AgentTaskState.REJECTED, errorCode);
    }

    private static AgentOperationResult unavailableOperation(String operationId, int errorCode) {
        return new AgentOperationResult(errorCode, operationId, 0L,
                AgentTaskState.REJECTED);
    }

    private IBinder modelServiceBinder() {
        return graph == null ? null : graph.modelBinder();
    }

    private IBinder downloadServiceBinder() {
        return graph == null ? null : graph.downloadBinder();
    }

    private IBinder voiceServiceBinder() {
        return graph == null ? null : graph.voiceBinder();
    }

    private static String requireTaskId(String taskId) {
        if (!AgentRequestValidator.isLowerUuid(taskId)) {
            throw new IllegalArgumentException("invalid taskId");
        }
        return taskId;
    }
}
