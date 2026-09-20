package com.matrix.agent.client;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.matrix.agent.api.voice.IVoiceCallback;
import com.matrix.agent.api.voice.IVoiceService;
import com.matrix.agent.api.voice.IVoiceSessionCallback;
import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;
import com.matrix.agent.api.voice.TencentTtsConfig;
import com.matrix.agent.api.voice.TencentTtsProvisionInput;
import com.matrix.agent.api.voice.IVoiceTtsConfigCallback;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.download.ModelDownloadInfo;

import java.util.List;
import java.util.Collections;
import java.util.Objects;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 语音域 Manager：状态、受控启停与"按住说话"会话；不暴露任何音频流。
 * 状态订阅与会话回调均为纯 Java listener（SDK 内部桥接 AIDL 并在门面事件 Handler
 * 上派发）；断线时返回稳定不可用结果，重连后自动重注册状态订阅。
 */
public final class VoiceManager extends MatrixManagerBase {

    private volatile IVoiceService service;
    private final List<IVoiceCallback> statusListeners = new CopyOnWriteArrayList<>();
    /** Linearizes local listener changes with the corresponding remote subscription change. */
    private final Object statusSubscriptionLock = new Object();
    private boolean statusBridgeRegistered;

    /** binder callback → 客户端 listener 的事件桥；在门面事件 Handler 上派发。 */
    private final IVoiceCallback.Stub statusBridge = new IVoiceCallback.Stub() {
        @Override
        public void onVoiceStatusChanged(VoiceServiceStatus status) {
            VoiceServiceStatus snapshot = status;
            eventHandler().post(() -> {
                for (IVoiceCallback listener : statusListeners) {
                    try {
                        listener.onVoiceStatusChanged(snapshot);
                    } catch (RemoteException e) {
                        // 客户端本地 listener 不应抛 RemoteException；防御性降级为日志
                        Log.w("VoiceManager", "status listener threw", e);
                    }
                }
            });
        }
    };

    VoiceManager(MatrixAgent matrixAgent, IBinder serviceBinder) {
        super(matrixAgent, serviceBinder);
        service = IVoiceService.Stub.asInterface(serviceBinder);
    }

    public VoiceServiceStatus getStatus() {
        IVoiceService s = service;
        if (s == null) {
            return unavailableStatus();
        }
        try {
            return s.getStatus();
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailableStatus());
        }
    }

    public VoiceOperationResult setEnabled(boolean enabled, String clientOperationId) {
        IVoiceService s = service;
        if (s == null) {
            return unavailable(clientOperationId, null);
        }
        try {
            return s.setEnabled(enabled, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, null));
        }
    }

    public VoiceOperationResult cancelCurrentSession(String clientOperationId) {
        IVoiceService s = service;
        if (s == null) {
            return unavailable(clientOperationId, null);
        }
        try {
            return s.cancelCurrentSession(clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, null));
        }
    }

    /** listener 重载：SDK 桥接 AIDL 回调并在门面事件 Handler 上派发。 */
    public VoiceSessionHandle startUserInitiatedSession(VoiceSessionRequest request,
            String clientOperationId, VoiceSessionListener listener) {
        Objects.requireNonNull(listener, "listener");
        return startUserInitiatedSession(request, clientOperationId, bridgeOf(listener));
    }

    /** 低层扩展接口：直接暴露 AIDL callback；常规调用方用 listener 重载。 */
    public VoiceSessionHandle startUserInitiatedSession(VoiceSessionRequest request,
            String clientOperationId, IVoiceSessionCallback callback) {
        IVoiceService s = service;
        if (s == null) {
            return unavailableSession(callback);
        }
        try {
            return s.startUserInitiatedSession(request, clientOperationId, callback);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailableSession(callback));
        }
    }

    public VoiceOperationResult stopSession(String sessionId, String clientOperationId) {
        IVoiceService s = service;
        if (s == null) {
            return unavailable(clientOperationId, sessionId);
        }
        try {
            return s.stopSession(sessionId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, sessionId));
        }
    }

    /** PTT 松开发送：停止采音并冲刷当前识别结果（设计文档 §6.2 契约变更）。 */
    public VoiceOperationResult finishSession(String sessionId, String clientOperationId) {
        IVoiceService s = service;
        if (s == null) {
            return unavailable(clientOperationId, sessionId);
        }
        try {
            return s.finishSession(sessionId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, sessionId));
        }
    }

    /** Returns the Host-owned, persisted state of the bundled offline speech models. */
    public List<ModelDownloadInfo> listOfflineModels() {
        IVoiceService s = service;
        if (s == null) return Collections.emptyList();
        try {
            return s.listOfflineModels();
        } catch (RemoteException e) {
            return handleRemoteException(e, Collections.emptyList());
        }
    }

    /** Downloads and verifies missing speech models without starting a recording session. */
    public VoiceOperationResult installOfflineModels(String clientOperationId) {
        IVoiceService s = service;
        if (s == null) return unavailable(clientOperationId, null);
        try {
            return s.installOfflineModels(clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, null));
        }
    }

    /** Removes an idle offline speech model. A model in an active voice session is protected. */
    public VoiceOperationResult deleteOfflineModel(String modelId, String clientOperationId) {
        IVoiceService s = service;
        if (s == null) return unavailable(clientOperationId, null);
        try {
            return s.deleteOfflineModel(modelId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, null));
        }
    }

    /** 当前 ASR 引擎名（"VOSK" / "SHERPA"）；断线期返回 null。 */
    public String getAsrEngine() {
        IVoiceService s = service;
        if (s == null) return null;
        try {
            return s.getAsrEngine();
        } catch (RemoteException e) {
            handleRemoteException(e);
            return null;
        }
    }

    /** 切换 ASR 引擎（空闲期调用；下次语音装配生效）。 */
    public VoiceOperationResult setAsrEngine(String engine, String clientOperationId) {
        IVoiceService s = service;
        if (s == null) return unavailable(clientOperationId, null);
        try {
            return s.setAsrEngine(engine, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, null));
        }
    }

    /** Returns only safe configuration metadata; Host credentials are intentionally non-readable. */
    public TencentTtsConfig getTencentTtsConfig() {
        IVoiceService s = service;
        if (s == null) return new TencentTtsConfig(false, TencentTtsConfig.DEFAULT_VOICE_TYPE,
                TencentTtsConfig.DEFAULT_EMOTION, TencentTtsConfig.DEFAULT_EMOTION_INTENSITY);
        try {
            return s.getTencentTtsConfig();
        } catch (RemoteException e) {
            return handleRemoteException(e, new TencentTtsConfig(false,
                    TencentTtsConfig.DEFAULT_VOICE_TYPE, TencentTtsConfig.DEFAULT_EMOTION,
                    TencentTtsConfig.DEFAULT_EMOTION_INTENSITY));
        }
    }

    /**
     * Sends SecretId/SecretKey through a one-shot pipe. They are never placed in a Binder parcel
     * or returned by any API; callers retain ownership and should wipe both arrays after return.
     */
    public VoiceOperationResult provisionTencentTts(TencentTtsProvisionInput input,
            char[] secretId, char[] secretKey, String clientOperationId,
            TencentTtsConfigListener listener) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(secretId, "secretId");
        Objects.requireNonNull(secretKey, "secretKey");
        Objects.requireNonNull(listener, "listener");
        IVoiceService target = service;
        if (target == null) return unavailable(clientOperationId, null);
        ParcelFileDescriptor readEnd = null;
        ParcelFileDescriptor writeEnd = null;
        byte[] payload = null;
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            readEnd = pipe[0];
            writeEnd = pipe[1];
            payload = encodeTencentCredential(secretId, secretKey);
            IVoiceTtsConfigCallback bridge = new IVoiceTtsConfigCallback.Stub() {
                @Override public void onTencentTtsConfigured(String operationId, int code) {
                    eventHandler().post(() -> listener.onFinished(operationId, code));
                }
            };
            VoiceOperationResult accepted;
            try {
                accepted = target.provisionTencentTts(input, readEnd, clientOperationId, bridge);
            } finally {
                closeQuietly(readEnd);
                readEnd = null;
            }
            if (accepted == null || accepted.code != MatrixErrorCode.SUCCESS) return accepted;
            final ParcelFileDescriptor writer = writeEnd;
            final byte[] bytes = payload;
            if (!matrixAgent.executeCredentialPipe(() -> {
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        writer.getFileDescriptor())) {
                    out.write(bytes);
                    out.flush();
                } catch (Exception ignored) {
                    // The Host receives EOF and returns a stable failure through the callback.
                } finally {
                    Arrays.fill(bytes, (byte) 0);
                    closeQuietly(writer);
                }
            })) {
                Arrays.fill(bytes, (byte) 0);
                closeQuietly(writer);
                return unavailable(clientOperationId, null);
            }
            writeEnd = null;
            payload = null;
            return accepted;
        } catch (Exception error) {
            closeQuietly(readEnd);
            closeQuietly(writeEnd);
            if (payload != null) Arrays.fill(payload, (byte) 0);
            return unavailable(clientOperationId, null);
        }
    }

    public VoiceOperationResult clearTencentTts(String clientOperationId) {
        IVoiceService s = service;
        if (s == null) return unavailable(clientOperationId, null);
        try {
            return s.clearTencentTts(clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailable(clientOperationId, null));
        }
    }

    private static byte[] encodeTencentCredential(char[] secretId, char[] secretKey)
            throws CharacterCodingException {
        byte[] id = null;
        byte[] key = null;
        try {
            id = ModelManager.encodeUtf8Secret(secretId);
            key = ModelManager.encodeUtf8Secret(secretKey);
            if (id.length == 0 || key.length == 0 || id.length > 256 || key.length > 512) {
                throw new IllegalArgumentException("invalid Tencent credential length");
            }
            return ByteBuffer.allocate(Integer.BYTES + id.length + key.length)
                    .putInt(id.length).put(id).put(key).array();
        } finally {
            if (id != null) Arrays.fill(id, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (Exception ignored) { }
    }

    public interface TencentTtsConfigListener {
        void onFinished(String clientOperationId, int code);
    }

    /** listener 重载（常规入口）：close() 显式退订；重连后自动重注册。 */
    public AutoCloseable subscribeStatus(VoiceStatusListener listener) {
        Objects.requireNonNull(listener, "listener");
        return subscribeStatus(new IVoiceCallback.Stub() {
            @Override
            public void onVoiceStatusChanged(VoiceServiceStatus status) {
                eventHandler().post(() -> listener.onVoiceStatusChanged(status));
            }
        });
    }

    /**
     * 低层扩展接口：直接暴露 AIDL callback（要求调用方自行处理 RemoteException 语义）。
     * 常规调用方使用 {@link #subscribeStatus(VoiceStatusListener)}。
     */
    public AutoCloseable subscribeStatus(IVoiceCallback listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (statusSubscriptionLock) {
            statusListeners.add(listener);
            registerBridgeLocked();
        }
        return () -> {
            synchronized (statusSubscriptionLock) {
                if (!statusListeners.remove(listener) || !statusListeners.isEmpty()
                        || !statusBridgeRegistered) return;
                IVoiceService s = service;
                statusBridgeRegistered = false;
                if (s == null) return;
                try {
                    s.unsubscribeStatus(statusBridge);
                } catch (RemoteException e) {
                    handleRemoteException(e);
                }
            }
        };
    }

    /** Caller holds {@link #statusSubscriptionLock}. */
    private void registerBridgeLocked() {
        if (statusListeners.isEmpty() || statusBridgeRegistered) return;
        IVoiceService s = service;
        if (s == null) return;
        statusBridgeRegistered = true;
        try {
            s.subscribeStatus(statusBridge);
        } catch (RemoteException e) {
            statusBridgeRegistered = false;
            handleRemoteException(e);
        }
    }

    private IVoiceSessionCallback bridgeOf(VoiceSessionListener listener) {
        return new IVoiceSessionCallback.Stub() {
            @Override
            public void onSessionStateChanged(String sessionId, int state) {
                eventHandler().post(() -> listener.onSessionStateChanged(sessionId, state));
            }

            @Override
            public void onPartialText(String sessionId, String text) {
                eventHandler().post(() -> listener.onPartialText(sessionId, text));
            }

            @Override
            public void onFinalText(String sessionId, String text) {
                eventHandler().post(() -> listener.onFinalText(sessionId, text));
            }

            @Override
            public void onSessionError(String sessionId, int errorCode) {
                eventHandler().post(() -> listener.onSessionError(sessionId, errorCode));
            }
        };
    }

    /** 断线期控制操作的稳定不可用结果。 */
    private static VoiceOperationResult unavailable(String clientOperationId, String sessionId) {
        return new VoiceOperationResult(
                MatrixErrorCode.SERVICE_NOT_READY,
                clientOperationId, sessionId);
    }

    private VoiceSessionHandle unavailableSession(IVoiceSessionCallback callback) {
        if (callback != null) {
            eventHandler().post(() -> {
                try { callback.onSessionError(null, MatrixErrorCode.SERVICE_NOT_READY); }
                catch (RemoteException ignored) { }
            });
        }
        return null;
    }

    private static VoiceServiceStatus unavailableStatus() {
        return new VoiceServiceStatus(false, VoiceServiceStatus.SESSION_IDLE, null,
                MatrixErrorCode.SERVICE_NOT_READY);
    }

    @Override
    protected void onMatrixServiceDisconnected() {
        synchronized (statusSubscriptionLock) {
            service = null;
            statusBridgeRegistered = false;
        }
    }

    @Override
    protected void onMatrixServiceConnected(IBinder serviceBinder) {
        synchronized (statusSubscriptionLock) {
            service = IVoiceService.Stub.asInterface(serviceBinder);
            statusBridgeRegistered = false;
            registerBridgeLocked();
        }
    }
}
