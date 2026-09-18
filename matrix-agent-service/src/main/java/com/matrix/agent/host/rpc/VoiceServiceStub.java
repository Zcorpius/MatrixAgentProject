package com.matrix.agent.host.rpc;

import com.matrix.agent.task.durable.PersistenceGate;

import android.app.Application;
import android.os.RemoteException;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.common.ParcelSchema;
import com.matrix.agent.api.voice.IVoiceCallback;
import com.matrix.agent.api.voice.IVoiceService;
import com.matrix.agent.api.voice.IVoiceSessionCallback;
import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.VoiceRuntimeHolder;
import com.matrix.agent.voice.VoiceSessionListener;
import com.matrix.agent.voice.VoiceSessionState;
import com.matrix.agent.voice.VoskModelDownloader;
import com.matrix.agent.voice.VoskModelSpec;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Secure voice-domain facade. Audio never crosses Binder; only bounded text/status does. */
public final class VoiceServiceStub extends IVoiceService.Stub {
    private static final int MAX_TRANSCRIPT_UTF8 = 4 * 1024;

    private final PersistenceGate persistenceGate;
    private final ModelServiceStub.CallerResolver callerResolver;
    private final VoskModelDownloader modelDownloader;
    private final VoskModelSpec englishModel;
    private final VoskModelSpec chineseModel;
    private final ExecutorService modelDownloadExecutor;
    private final Object modelInstallLock = new Object();
    private boolean modelInstallInFlight;
    private final CallbackRegistry<IVoiceCallback> statusCallbacks = new CallbackRegistry<>();
    private final CallbackRegistry<IVoiceSessionCallback> sessionCallbacks =
            new CallbackRegistry<>();
    private final Object sessionLock = new Object();
    private volatile boolean enabled = true;
    private volatile String currentSessionId;

    public VoiceServiceStub(Application application, ModelDownloadDao downloads,
            ExecutorService modelDownloadExecutor,
            PersistenceGate gate, ModelServiceStub.CallerResolver resolver) {
        persistenceGate = gate;
        callerResolver = resolver;
        File root = new File(application.getFilesDir(), "vosk-model");
        modelDownloader = new VoskModelDownloader(application, downloads);
        englishModel = VoskModelSpec.en(root);
        chineseModel = VoskModelSpec.cn(root);
        this.modelDownloadExecutor = modelDownloadExecutor;
    }

    @Override public VoiceServiceStatus getStatus() {
        callerResolver.caller();
        return status();
    }

    @Override public VoiceOperationResult setEnabled(boolean requested, String operationId) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(operationId);
        if (!persistenceGate.isAvailable()) {
            return result(MatrixErrorCode.PERSISTENCE_UNAVAILABLE, operationId, null);
        }
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (runtime == null) return result(MatrixErrorCode.SERVICE_NOT_READY, operationId, null);
        enabled = requested;
        if (requested) runtime.resume(); else runtime.pause();
        if (!requested) finishSession(VoiceServiceStatus.SESSION_IDLE, MatrixErrorCode.SUCCESS);
        dispatchStatus();
        return result(MatrixErrorCode.SUCCESS, operationId, currentSessionId);
    }

    @Override public VoiceOperationResult cancelCurrentSession(String operationId) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(operationId);
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (runtime == null) return result(MatrixErrorCode.SERVICE_NOT_READY, operationId, null);
        String session = currentSessionId;
        if (session == null) return result(MatrixErrorCode.NOT_FOUND, operationId, null);
        runtime.cancel();
        finishSession(VoiceServiceStatus.SESSION_IDLE, MatrixErrorCode.SUCCESS);
        return result(MatrixErrorCode.SUCCESS, operationId, session);
    }

    @Override public VoiceSessionHandle startUserInitiatedSession(VoiceSessionRequest request,
            String operationId, IVoiceSessionCallback callback) throws RemoteException {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        validateRequest(request);
        if (callback == null) throw new IllegalArgumentException("callback required");
        if (!persistenceGate.isAvailable()) {
            callback.onSessionError(null, MatrixErrorCode.PERSISTENCE_UNAVAILABLE);
            return null;
        }
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (runtime == null || !enabled) {
            callback.onSessionError(null, MatrixErrorCode.SERVICE_NOT_READY);
            return null;
        }
        final String sessionId;
        synchronized (sessionLock) {
            if (currentSessionId != null || runtime.isSessionActive()) {
                callback.onSessionError(currentSessionId, MatrixErrorCode.OVERLOADED);
                return null;
            }
            sessionId = UUID.randomUUID().toString();
            currentSessionId = sessionId;
            try {
                sessionCallbacks.add(callback);
            } catch (RemoteException dead) {
                currentSessionId = null;
                throw dead;
            }
            runtime.setUiListener(new BinderVoiceListener(sessionId));
        }
        // VoiceRuntime deliberately refuses to assemble or open the microphone while it is
        // considered background.  A Binder PTT request is itself the foreground, user-initiated
        // entry point, so make that lifecycle transition before scheduling the asynchronous
        // preparation.  Waiting until onReady here deadlocks a cold runtime: buildAndStart()
        // would only set pendingBuild and no later lifecycle callback would retry it.
        runtime.resume();
        runtime.start(null, () -> beginListening(runtime, sessionId),
                () -> failStartup(sessionId));
        return new VoiceSessionHandle(sessionId, safeOperation,
                VoiceServiceStatus.SESSION_LISTENING);
    }

    @Override public VoiceOperationResult stopSession(String sessionId, String operationId) {
        callerResolver.caller();
        String safeSession = HostInputValidator.requireSessionId(sessionId);
        HostInputValidator.requireOperationId(operationId);
        if (!safeSession.equals(currentSessionId)) {
            return result(MatrixErrorCode.NOT_FOUND, operationId, safeSession);
        }
        return cancelCurrentSession(operationId);
    }

    @Override public List<ModelDownloadInfo> listOfflineModels() {
        callerResolver.caller();
        // Keep this independent from persistence availability: active-pointer/marker inspection
        // still accurately exposes already-installed models when Room is temporarily degraded.
        return Arrays.asList(modelDownloader.modelInfo(englishModel),
                modelDownloader.modelInfo(chineseModel));
    }

    @Override public VoiceOperationResult installOfflineModels(String operationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        if (!persistenceGate.isAvailable()) {
            return result(MatrixErrorCode.PERSISTENCE_UNAVAILABLE, safeOperation, null);
        }
        synchronized (modelInstallLock) {
            if (modelInstallInFlight) return result(MatrixErrorCode.OVERLOADED, safeOperation, null);
            modelInstallInFlight = true;
        }
        try {
            modelDownloadExecutor.execute(() -> {
                try {
                    installIfMissing(englishModel);
                    installIfMissing(chineseModel);
                } catch (IOException failure) {
                    android.util.Log.w("MatrixAgent", "[Voice] 离线模型安装未完成: "
                            + failure.getClass().getSimpleName());
                } finally {
                    synchronized (modelInstallLock) { modelInstallInFlight = false; }
                }
            });
            return result(MatrixErrorCode.SUCCESS, safeOperation, null);
        } catch (RejectedExecutionException unavailable) {
            synchronized (modelInstallLock) { modelInstallInFlight = false; }
            return result(MatrixErrorCode.OVERLOADED, safeOperation, null);
        }
    }

    @Override public VoiceOperationResult deleteOfflineModel(String modelId, String operationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        VoskModelSpec model = modelFor(modelId);
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        // The Vosk native model can retain file mappings after a session has ended.  Never unlink
        // a loaded mapping: users can delete it safely before first use or after a Host restart.
        if (currentSessionId != null || (runtime != null
                && (runtime.isSessionActive() || runtime.hasLoadedModels()))) {
            return result(MatrixErrorCode.UNSUPPORTED_OPERATION, safeOperation, null);
        }
        try {
            modelDownloader.delete(model);
            return result(MatrixErrorCode.SUCCESS, safeOperation, null);
        } catch (IOException e) {
            String message = e.getMessage();
            int code = message != null && message.startsWith("MODEL_BUSY")
                    ? MatrixErrorCode.OVERLOADED : MatrixErrorCode.TASK_FAILED;
            return result(code, safeOperation, null);
        }
    }

    @Override public void subscribeStatus(IVoiceCallback callback) throws RemoteException {
        callerResolver.caller();
        if (callback == null) throw new IllegalArgumentException("callback required");
        statusCallbacks.add(callback);
        callback.onVoiceStatusChanged(status());
    }

    @Override public void unsubscribeStatus(IVoiceCallback callback) {
        callerResolver.caller();
        statusCallbacks.remove(callback);
    }

    private void validateRequest(VoiceSessionRequest request) {
        if (request == null || request.schemaVersion != ParcelSchema.CURRENT
                || request.triggerSource != VoiceSessionRequest.TRIGGER_PTT) {
            throw new IllegalArgumentException("invalid voice session request");
        }
        HostInputValidator.requireLanguageTag(request.languageTag);
    }

    private VoskModelSpec modelFor(String modelId) {
        if (englishModel.name.equals(modelId)) return englishModel;
        if (chineseModel.name.equals(modelId)) return chineseModel;
        throw new IllegalArgumentException("unknown offline voice model");
    }

    private void installIfMissing(VoskModelSpec model) throws IOException {
        if (!modelDownloader.isDownloaded(model)) modelDownloader.download(model, () -> false);
    }

    private final class BinderVoiceListener implements VoiceSessionListener {
        private final String sessionId;
        BinderVoiceListener(String sessionId) { this.sessionId = sessionId; }

        @Override public void onPartial(String text) {
            if (!isCurrent(sessionId)) return;
            String safe = HostInputValidator.boundUtf8(text, MAX_TRANSCRIPT_UTF8);
            sessionCallbacks.dispatch(callback -> callback.onPartialText(sessionId, safe));
        }

        @Override public void onFinal(String text) {
            if (!isCurrent(sessionId)) return;
            String safe = HostInputValidator.boundUtf8(text, MAX_TRANSCRIPT_UTF8);
            sessionCallbacks.dispatch(callback -> callback.onFinalText(sessionId, safe));
        }

        @Override public void onStateChanged(VoiceSessionState.State state) {
            if (!isCurrent(sessionId)) return;
            int publicState = publicState(state);
            dispatchSessionState(sessionId, publicState);
            dispatchStatus();
            if (state == VoiceSessionState.State.IDLE) {
                finishSession(publicState, MatrixErrorCode.SUCCESS);
            }
        }

        @Override public void onError(String code) {
            if (!isCurrent(sessionId)) return;
            finishSession(VoiceServiceStatus.SESSION_IDLE, MatrixErrorCode.TASK_FAILED);
        }
    }

    private boolean isCurrent(String sessionId) {
        return sessionId.equals(currentSessionId);
    }

    private void beginListening(VoiceRuntime runtime, String sessionId) {
        if (!isCurrent(sessionId) || !enabled) return;
        runtime.resume();
        runtime.manualWake("binder_ptt");
        dispatchSessionState(sessionId, VoiceServiceStatus.SESSION_LISTENING);
        dispatchStatus();
    }

    private void failStartup(String sessionId) {
        if (isCurrent(sessionId)) {
            finishSession(VoiceServiceStatus.SESSION_IDLE, MatrixErrorCode.TASK_FAILED);
        }
    }

    private void dispatchSessionState(String sessionId, int state) {
        sessionCallbacks.dispatch(callback -> callback.onSessionStateChanged(sessionId, state));
    }

    private void finishSession(int finalState, int errorCode) {
        synchronized (sessionLock) {
            String session = currentSessionId;
            if (session == null) return;
            currentSessionId = null;
            VoiceRuntime runtime = VoiceRuntimeHolder.get();
            if (runtime != null) runtime.setUiListener(null);
            // sessionCallbacks belongs to the single active session.  Complete and clear it
            // before releasing sessionLock: otherwise a new PTT request can register its
            // callback between currentSessionId=null and clear(), and the old session would
            // silently erase the new session's callback.
            dispatchSessionState(session, finalState);
            if (errorCode != MatrixErrorCode.SUCCESS) {
                sessionCallbacks.dispatch(callback -> callback.onSessionError(session, errorCode));
            }
            sessionCallbacks.clear();
        }
        dispatchStatus();
    }

    private VoiceServiceStatus status() {
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        int state = runtime != null && runtime.isSessionActive()
                ? VoiceServiceStatus.SESSION_LISTENING : VoiceServiceStatus.SESSION_IDLE;
        return new VoiceServiceStatus(enabled && runtime != null, state, currentSessionId,
                runtime == null ? MatrixErrorCode.SERVICE_NOT_READY : MatrixErrorCode.SUCCESS);
    }

    private void dispatchStatus() {
        VoiceServiceStatus snapshot = status();
        statusCallbacks.dispatch(callback -> callback.onVoiceStatusChanged(snapshot));
    }

    private static int publicState(VoiceSessionState.State state) {
        if (state == VoiceSessionState.State.THINKING
                || state == VoiceSessionState.State.CONFIRMING) {
            return VoiceServiceStatus.SESSION_THINKING;
        }
        if (state == VoiceSessionState.State.SPEAKING) {
            return VoiceServiceStatus.SESSION_SPEAKING;
        }
        if (state == VoiceSessionState.State.IDLE
                || state == VoiceSessionState.State.CANCELLED
                || state == VoiceSessionState.State.ERROR_ANNOUNCING) {
            return VoiceServiceStatus.SESSION_IDLE;
        }
        return VoiceServiceStatus.SESSION_LISTENING;
    }

    public void shutdown() {
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (runtime != null) runtime.setUiListener(null);
        currentSessionId = null;
        sessionCallbacks.clear();
        statusCallbacks.clear();
    }

    private static VoiceOperationResult result(int code, String operationId, String sessionId) {
        return new VoiceOperationResult(code, operationId, sessionId);
    }
}
