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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
        ParcelFileDescriptor readEnd = null;
        ParcelFileDescriptor writeEnd = null;
        byte[] bytes = null;
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readEnd = pipe[0];
            writeEnd = pipe[1];
            // Do not create new String(secret): that immutable copy cannot be wiped after the
            // one-shot pipe write.  The temporary byte buffer has a clear ownership hand-off.
            bytes = encodeUtf8Secret(secret);
            ModelOperationHandle handle;
            try {
                handle = target.provisionCredential(input, readEnd, clientOperationId, bridge);
            } finally {
                closeQuietly(readEnd);
                readEnd = null;
            }
            final ParcelFileDescriptor writerEnd = writeEnd;
            final byte[] writerBytes = bytes;
            Runnable writer = () -> {
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                        writerEnd.getFileDescriptor())) {
                    output.write(writerBytes);
                    output.flush();
                } catch (Exception ignored) {
                    // Host reports a bounded INVALID_ARGUMENT/TASK_FAILED callback if the pipe fails.
                } finally {
                    Arrays.fill(writerBytes, (byte) 0);
                    closeQuietly(writerEnd);
                }
            };
            if (!matrixAgent.executeCredentialPipe(writer)) {
                Arrays.fill(writerBytes, (byte) 0);
                closeQuietly(writerEnd);
                return unavailable(clientOperationId, input.providerId, bridge);
            }
            // The bounded SDK lane owns both resources only after acceptance.  If it rejects,
            // close/wipe synchronously so the Host's one-shot pipe reader observes EOF.
            writeEnd = null;
            bytes = null;
            return handle;
        } catch (Exception error) {
            closeQuietly(readEnd);
            closeQuietly(writeEnd);
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            return unavailable(clientOperationId, input.providerId, bridge);
        }
    }

    /** Encodes a credential without materialising its caller-owned char[] as an immutable String. */
    static byte[] encodeUtf8Secret(char[] secret) throws CharacterCodingException {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer encoded = null;
        try {
            encoded = encoder.encode(CharBuffer.wrap(secret));
            byte[] copy = new byte[encoded.remaining()];
            encoded.get(copy);
            return copy;
        } finally {
            if (encoded != null && encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try {
            descriptor.close();
        } catch (Exception ignored) {
            // The opposite pipe end may already have been closed by Binder/Host.
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
