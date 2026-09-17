package com.matrix.agent.client;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.matrix.agent.api.model.ConnectionTestResult;
import com.matrix.agent.api.model.IModelCallback;
import com.matrix.agent.api.model.IModelService;
import com.matrix.agent.api.model.ModelConfigInput;
import com.matrix.agent.api.model.ModelInfo;
import com.matrix.agent.api.model.ModelOperationHandle;
import com.matrix.agent.api.model.ModelProvisionInput;
import com.matrix.agent.api.model.ModelRuntimeStatus;
import com.matrix.agent.api.common.MatrixErrorCode;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;

/** 模型域 Manager：选择与状态；下载传输走 DownloadManager。断线时返回稳定不可用结果。 */
public final class ModelManager extends MatrixManagerBase {

    private volatile IModelService service;

    ModelManager(MatrixAgent matrixAgent, IBinder serviceBinder) {
        super(matrixAgent, serviceBinder);
        service = IModelService.Stub.asInterface(serviceBinder);
    }

    public List<ModelInfo> listModels() {
        IModelService s = service;
        if (s == null) {
            return Collections.emptyList();
        }
        try {
            return s.listModels();
        } catch (RemoteException e) {
            return handleRemoteException(e, Collections.emptyList());
        }
    }

    public ModelRuntimeStatus getRuntimeStatus() {
        IModelService s = service;
        if (s == null) {
            return unavailableRuntime();
        }
        try {
            return s.getRuntimeStatus();
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailableRuntime());
        }
    }

    public ConnectionTestResult testConnection(ModelConfigInput config) {
        IModelService s = service;
        if (s == null) {
            return unavailableConnection();
        }
        try {
            return s.testConnection(config);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailableConnection());
        }
    }

    /**
     * Stores a credential only in Host's keystore domain. The secret itself is streamed through
     * an anonymous one-shot pipe, not encoded in the Binder parcel; callers should zero the
     * supplied char array after this method returns.
     */
    public ModelOperationHandle provisionCredential(String providerId, char[] secret,
            String clientOperationId, OperationListener listener) {
        return provisionCredential(new ModelProvisionInput(providerId, null, null), secret,
                clientOperationId, listener);
    }

    /**
     * Provisions a code-signed provider and an optional provider-scoped model identifier.
     * Arbitrary endpoint/header values intentionally have no SDK representation.
     */
    public ModelOperationHandle provisionCredential(ModelProvisionInput input, char[] secret,
            String clientOperationId, OperationListener listener) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(listener, "listener");
        IModelCallback bridge = new IModelCallback.Stub() {
            @Override public void onModelOperationFinished(ModelOperationHandle handle, int code) {
                eventHandler().post(() -> listener.onFinished(handle, code));
            }
        };
        IModelService target = service;
        if (target == null) return unavailable(clientOperationId, input.providerId, bridge);
        ParcelFileDescriptor[] pipe;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            byte[] bytes = new String(secret).getBytes(StandardCharsets.UTF_8);
            ModelOperationHandle handle = target.provisionCredential(input, pipe[0],
                    clientOperationId, bridge);
            pipe[0].close();
            Thread writer = new Thread(() -> {
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                        pipe[1].getFileDescriptor())) {
                    output.write(bytes);
                    output.flush();
                } catch (Exception ignored) {
                    // Host reports a bounded INVALID_ARGUMENT/TASK_FAILED callback if the pipe fails.
                } finally {
                    java.util.Arrays.fill(bytes, (byte) 0);
                    try { pipe[1].close(); } catch (Exception ignored) { }
                }
            }, "matrix-credential-pipe");
            writer.setDaemon(true);
            writer.start();
            return handle;
        } catch (Exception error) {
            return unavailable(clientOperationId, input.providerId, bridge);
        }
    }

    /** listener 重载：SDK 桥接 AIDL 回调并在门面事件 Handler 上派发，调用方无需接触 Stub。 */
    public ModelOperationHandle setActiveModel(String modelId, String clientOperationId,
            OperationListener listener) {
        Objects.requireNonNull(listener, "listener");
        return setActiveModel(modelId, clientOperationId, new IModelCallback.Stub() {
            @Override
            public void onModelOperationFinished(ModelOperationHandle handle, int errorCode) {
                eventHandler().post(() -> listener.onFinished(handle, errorCode));
            }
        });
    }

    /** 低层扩展接口：直接暴露 AIDL callback；常规调用方用 listener 重载。 */
    public ModelOperationHandle setActiveModel(String modelId, String clientOperationId,
            IModelCallback callback) {
        IModelService s = service;
        if (s == null) {
            return unavailable(clientOperationId, modelId, callback);
        }
        try {
            return s.setActiveModel(modelId, clientOperationId, callback);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, modelId, callback));
        }
    }

    private ModelOperationHandle unavailable(String operationId, String modelId,
            IModelCallback callback) {
        ModelOperationHandle result = new ModelOperationHandle(operationId, modelId,
                ModelOperationHandle.STATE_FAILED);
        if (callback != null) {
            eventHandler().post(() -> {
                try {
                    callback.onModelOperationFinished(result,
                            com.matrix.agent.api.common.MatrixErrorCode.SERVICE_NOT_READY);
                } catch (RemoteException ignored) { }
            });
        }
        return result;
    }

    private static ModelRuntimeStatus unavailableRuntime() {
        return new ModelRuntimeStatus(null, false, ModelRuntimeStatus.BACKEND_NONE,
                MatrixErrorCode.SERVICE_NOT_READY);
    }

    private static ConnectionTestResult unavailableConnection() {
        return new ConnectionTestResult(false, 0L, MatrixErrorCode.SERVICE_NOT_READY,
                "Host service unavailable");
    }

    @Override
    protected void onMatrixServiceDisconnected() {
        service = null;
    }

    @Override
    protected void onMatrixServiceConnected(IBinder serviceBinder) {
        service = IModelService.Stub.asInterface(serviceBinder);
    }
}
