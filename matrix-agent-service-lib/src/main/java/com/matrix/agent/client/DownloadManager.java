package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;

import com.matrix.agent.api.download.IDownloadService;
import com.matrix.agent.api.download.ModelCatalogItem;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.api.model.IModelCallback;
import com.matrix.agent.api.model.ModelOperationHandle;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 下载域 Manager：目录（本地验签缓存只读）、下载列表与受控操作。断线时返回稳定不可用结果。 */
public final class DownloadManager extends MatrixManagerBase {

    private volatile IDownloadService service;

    DownloadManager(MatrixAgent matrixAgent, IBinder serviceBinder) {
        super(matrixAgent, serviceBinder);
        service = IDownloadService.Stub.asInterface(serviceBinder);
    }

    public List<ModelCatalogItem> listCatalog() {
        IDownloadService s = service;
        if (s == null) {
            return Collections.emptyList();
        }
        try {
            return s.listCatalog();
        } catch (RemoteException e) {
            return handleRemoteException(e, Collections.emptyList());
        }
    }

    public List<ModelDownloadInfo> listDownloads() {
        IDownloadService s = service;
        if (s == null) {
            return Collections.emptyList();
        }
        try {
            return s.listDownloads();
        } catch (RemoteException e) {
            return handleRemoteException(e, Collections.emptyList());
        }
    }

    /** Refreshes the Host-owned remote market cache; subsequent listCatalog calls read that cache. */
    public ModelOperationHandle refreshCatalog(String clientOperationId, OperationListener listener) {
        IDownloadService s = service;
        IModelCallback callback = bridgeOf(listener);
        if (s == null) return unavailable(clientOperationId, "mnn-market", callback);
        try {
            return s.refreshCatalog(clientOperationId, callback);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, "mnn-market", callback));
        }
    }

    /** listener 重载：SDK 桥接 AIDL 回调并在门面事件 Handler 上派发。 */
    public ModelOperationHandle install(String catalogModelId, String clientOperationId,
            OperationListener listener) {
        return install(catalogModelId, clientOperationId, bridgeOf(listener));
    }

    /** 低层扩展接口：直接暴露 AIDL callback；常规调用方用 listener 重载。 */
    public ModelOperationHandle install(String catalogModelId, String clientOperationId,
            IModelCallback callback) {
        IDownloadService s = service;
        if (s == null) {
            return unavailable(clientOperationId, catalogModelId, callback);
        }
        try {
            return s.install(catalogModelId, clientOperationId, callback);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, catalogModelId, callback));
        }
    }

    public ModelOperationHandle pause(String modelId, String clientOperationId) {
        IDownloadService s = service;
        if (s == null) {
            return unavailable(clientOperationId, modelId, null);
        }
        try {
            return s.pause(modelId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, modelId, null));
        }
    }

    /** listener 重载：SDK 桥接 AIDL 回调并在门面事件 Handler 上派发。 */
    public ModelOperationHandle resume(String modelId, String clientOperationId,
            OperationListener listener) {
        return resume(modelId, clientOperationId, bridgeOf(listener));
    }

    /** 低层扩展接口：直接暴露 AIDL callback；常规调用方用 listener 重载。 */
    public ModelOperationHandle resume(String modelId, String clientOperationId,
            IModelCallback callback) {
        IDownloadService s = service;
        if (s == null) {
            return unavailable(clientOperationId, modelId, callback);
        }
        try {
            return s.resume(modelId, clientOperationId, callback);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, modelId, callback));
        }
    }

    /**
     * Deletes a local model asynchronously. Prefer the listener overload so the caller receives
     * the authoritative terminal result rather than inferring success from a later list refresh.
     */
    public ModelOperationHandle delete(String modelId, String clientOperationId,
            OperationListener listener) {
        return delete(modelId, clientOperationId, bridgeOf(listener));
    }

    /** Low-level AIDL callback overload; regular callers should use {@link OperationListener}. */
    public ModelOperationHandle delete(String modelId, String clientOperationId,
            IModelCallback callback) {
        IDownloadService s = service;
        if (s == null) {
            return unavailable(clientOperationId, modelId, callback);
        }
        try {
            return s.delete(modelId, clientOperationId, callback);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, modelId, callback));
        }
    }

    /** Source-compatible no-listener form; use only when a later projection refresh is enough. */
    public ModelOperationHandle delete(String modelId, String clientOperationId) {
        return delete(modelId, clientOperationId, (IModelCallback) null);
    }

    private IModelCallback bridgeOf(OperationListener listener) {
        Objects.requireNonNull(listener, "listener");
        return new IModelCallback.Stub() {
            @Override
            public void onModelOperationFinished(ModelOperationHandle handle, int errorCode) {
                eventHandler().post(() -> listener.onFinished(handle, errorCode));
            }
        };
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

    @Override
    protected void onMatrixServiceDisconnected() {
        service = null;
    }

    @Override
    protected void onMatrixServiceConnected(IBinder serviceBinder) {
        service = IDownloadService.Stub.asInterface(serviceBinder);
    }
}
