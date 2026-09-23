package com.matrix.agent.host.rpc;

import com.matrix.agent.task.durable.PersistenceGate;

import android.app.Application;
import android.os.RemoteException;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.common.ParcelSchema;
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
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.voice.AsrEngineSelection;
import com.matrix.agent.voice.VoiceEnginePreference;
import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.VoiceRuntimeHolder;
import com.matrix.agent.voice.VoiceSessionListener;
import com.matrix.agent.voice.VoiceSessionState;
import com.matrix.agent.voice.VoskModelDownloader;
import com.matrix.agent.voice.VoskModelSpec;
import com.matrix.agent.voice.sherpa.SherpaModelDownloader;
import com.matrix.agent.voice.sherpa.SherpaModelSpec;
import com.matrix.agent.voice.system.SystemVoiceRuntimeOwner;
import com.matrix.agent.voice.tencent.SecureTencentTtsConfigStore;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * Secure voice-domain facade. Audio never crosses Binder; only bounded text/status does.
 *
 * <p>离线模型 RPC（list/install/delete）按 {@link VoiceEnginePreference} 选中的 ASR 引擎
 * 路由：VOSK → 英/中 zip 双模型；SHERPA → ASR/VAD/KWS 三件套（tar.bz2 管道）。
 * 引擎切换（{@link #setAsrEngine}）在空闲期回收当前 Runtime，下次语音使用懒重建。</p>
 */
public final class VoiceServiceStub extends IVoiceService.Stub {
    private static final int MAX_TRANSCRIPT_UTF8 = 4 * 1024;

    private final Application application;
    private final PersistenceGate persistenceGate;
    private final ModelServiceStub.CallerResolver callerResolver;
    private final VoiceEnginePreference enginePreference;
    private final VoskModelDownloader modelDownloader;
    private final VoskModelSpec englishModel;
    private final VoskModelSpec chineseModel;
    private final SherpaModelDownloader sherpaDownloader;
    private final SherpaModelSpec sherpaAsr;
    private final SherpaModelSpec sherpaVad;
    private final SherpaModelSpec sherpaKws;
    /** ASR 无关的应用内 Piper 播报模型；Vosk / Sherpa 都可复用。 */
    private final SherpaModelSpec sherpaTts;
    private final ExecutorService modelDownloadExecutor;
    private final SecureTencentTtsConfigStore tencentTtsConfig;
    private final Object modelInstallLock = new Object();
    private boolean modelInstallInFlight;
    private final CallbackRegistry<IVoiceCallback> statusCallbacks = new CallbackRegistry<>();
    /** 对话页 PTT 绑定存储——由 ConversationGraph 注入；null 表示对话域未装配。 */
    private volatile com.matrix.agent.conversation.ConversationVoiceBindingStore bindingStore;
    private final CallbackRegistry<IVoiceSessionCallback> sessionCallbacks =
            new CallbackRegistry<>();
    private final Object sessionLock = new Object();
    private volatile boolean enabled = true;
    private volatile String currentSessionId;
    private volatile Consumer<com.matrix.agent.voice.VoiceSessionController> controllerConfigurer;
    /** In-process readback output is separately owned; refresh it after an idle route change. */
    private volatile Runnable ttsOutputRouteChangedListener = () -> { };

    public VoiceServiceStub(Application application, ModelDownloadDao downloads,
            ExecutorService modelDownloadExecutor,
            PersistenceGate gate, ModelServiceStub.CallerResolver resolver) {
        this.application = application;
        persistenceGate = gate;
        callerResolver = resolver;
        enginePreference = new VoiceEnginePreference(application);
        File voskRoot = new File(application.getFilesDir(), "vosk-model");
        modelDownloader = new VoskModelDownloader(application, downloads);
        englishModel = VoskModelSpec.en(voskRoot);
        chineseModel = VoskModelSpec.cn(voskRoot);
        File sherpaRoot = new File(application.getFilesDir(), "sherpa-model");
        sherpaDownloader = new SherpaModelDownloader(application, downloads,
                new com.matrix.agent.platform.MatrixHttpClient().download(),
                SherpaModelDownloader.systemPresetRoot());
        sherpaAsr = SherpaModelSpec.streamingBilingual(sherpaRoot);
        sherpaVad = SherpaModelSpec.sileroVad(sherpaRoot);
        sherpaKws = SherpaModelSpec.kwsZhEn(sherpaRoot);
        sherpaTts = SherpaModelSpec.piperZhCn(sherpaRoot);
        this.modelDownloadExecutor = modelDownloadExecutor;
        tencentTtsConfig = new SecureTencentTtsConfigStore(application);
    }

    public void setTtsOutputRouteChangedListener(Runnable listener) {
        ttsOutputRouteChangedListener = listener == null ? () -> { } : listener;
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
        finishSession(session, VoiceServiceStatus.SESSION_IDLE, MatrixErrorCode.SUCCESS);
        return result(MatrixErrorCode.SUCCESS, operationId, session);
    }

    @Override public VoiceSessionHandle startUserInitiatedSession(VoiceSessionRequest request,
            String operationId, IVoiceSessionCallback callback) throws RemoteException {
        Log.i("MatrixAgent", "[VoiceSession] PTT enter op=" + operationId
                + " runtimeBuilt=" + (VoiceRuntimeHolder.get() != null));
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        validateRequest(request);
        // 尝试领取对话绑定：operationId 可能是对话页创建的 bindingOperationId。
        // 命中时该 PTT 会话的 final 投递到绑定的 conversation；未命中走独立语音路径。
        String boundConversationId = null;
        com.matrix.agent.conversation.ConversationVoiceBindingStore bindings = bindingStore;
        if (bindings != null) {
            boundConversationId = bindings.consume(safeOperation,
                    com.matrix.agent.identity.ActorUsers.USER_DRIVER);
        }
        if (callback == null) throw new IllegalArgumentException("callback required");
        if (!persistenceGate.isAvailable()) {
            Log.w("MatrixAgent", "[VoiceSession] PTT rejected reason=persistence_unavailable");
            callback.onSessionError(null, MatrixErrorCode.PERSISTENCE_UNAVAILABLE);
            return null;
        }
        VoiceRuntime heldRuntime = VoiceRuntimeHolder.get();
        if (heldRuntime == null) {
            // 引擎热切换（setAsrEngine 回收 Runtime）或冷启动后的懒重建：经进程唯一
            // Owner 构造（getOrCreate 只建不启动、不开麦——权限边界不变），
            // 启动仍由下方显式 UI 触发的 runtime.start(...) 驱动。
            // 不重建的话 Binder PTT 在引擎切换后永远 SERVICE_NOT_READY（真机闭环 D-验证发现）。
            heldRuntime = SystemVoiceRuntimeOwner.shared(application).getOrCreate();
        }
        final VoiceRuntime runtime = heldRuntime;
        Consumer<com.matrix.agent.voice.VoiceSessionController> configurer = controllerConfigurer;
        if (configurer != null) runtime.addControllerConfigurer(configurer);
        if (runtime == null || !enabled) {
            Log.w("MatrixAgent", "[VoiceSession] PTT rejected reason="
                    + (runtime == null ? "runtime_unavailable" : "voice_disabled"));
            callback.onSessionError(null, MatrixErrorCode.SERVICE_NOT_READY);
            return null;
        }
        final String sessionId;
        synchronized (sessionLock) {
            if (currentSessionId != null || runtime.isSessionActive()) {
                Log.w("MatrixAgent", "[VoiceSession] PTT rejected reason=session_busy");
                callback.onSessionError(currentSessionId, MatrixErrorCode.OVERLOADED);
                return null;
            }
            sessionId = UUID.randomUUID().toString();
            currentSessionId = sessionId;
            // 无论是否绑定都必须设置真实 sid：PTT 幂等键与语音回注 token 都以它为根。
            // Runtime 尚未装配 controller 时会暂存并在发布前补注入。
            runtime.setVoiceSessionContext(sessionId, boundConversationId);
            try {
                sessionCallbacks.add(callback);
            } catch (RemoteException dead) {
                currentSessionId = null;
                throw dead;
            }
            runtime.setUiListener(new BinderVoiceListener(sessionId));
        }
        Log.i("MatrixAgent", "[VoiceSession] PTT accepted language=" + request.languageTag
                + " runtimeBuilt=" + runtime.hasLoadedModels());
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

    @Override public VoiceOperationResult finishSession(String sessionId,
            String operationId) {
        callerResolver.caller();
        String safeSession = HostInputValidator.requireSessionId(sessionId);
        HostInputValidator.requireOperationId(operationId);
        if (!safeSession.equals(currentSessionId)) {
            return result(MatrixErrorCode.NOT_FOUND, operationId, safeSession);
        }
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (runtime == null) {
            return result(MatrixErrorCode.SERVICE_NOT_READY, operationId, null);
        }
        var controller = runtime.getController();
        if (controller == null || controller.currentState() == VoiceSessionState.State.IDLE) {
            // PTT 是有界的按住手势。若松手时运行时仍在装配（controller 为 null）或已发布但
            // beginListening 的唤醒尚未派发（IDLE），直接结束本次 Host 会话；运行时可继续
            // 预热，但 beginListening 的 isCurrent 门卫会丢弃旧 session 的迟到唤醒，
            // 绝不允许它在后台占住下一次 PTT。
            Log.i("MatrixAgent", "[VoiceSession] PTT released before listen dispatch; closing session");
            finishSession(safeSession, VoiceServiceStatus.SESSION_IDLE, MatrixErrorCode.SUCCESS);
            return result(MatrixErrorCode.SUCCESS, operationId, safeSession);
        }
        // flush = 停止采音 + 强制产出 final。PTT session 已活跃，不应再 manualWake；那会
        // 在 WAKE_ACCEPTED/LISTENING 之间制造无效的重复 wake。
        controller.flushFinalForPtt();
        return result(MatrixErrorCode.SUCCESS, operationId, safeSession);
    }

    @Override public List<ModelDownloadInfo> listOfflineModels() {
        callerResolver.caller();
        // Keep this independent from persistence availability: active-pointer/marker inspection
        // still accurately exposes already-installed models when Room is temporarily degraded.
        return enginePreference.isSherpaSelected()
                ? Arrays.asList(sherpaDownloader.modelInfo(sherpaAsr),
                        sherpaDownloader.modelInfo(sherpaVad),
                        sherpaDownloader.modelInfo(sherpaKws),
                        sherpaDownloader.modelInfo(sherpaTts))
                : Arrays.asList(modelDownloader.modelInfo(englishModel),
                        modelDownloader.modelInfo(chineseModel),
                        sherpaDownloader.modelInfo(sherpaTts));
    }

    @Override public VoiceOperationResult installOfflineModels(String operationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        if (!persistenceGate.isAvailable()) {
            return result(MatrixErrorCode.PERSISTENCE_UNAVAILABLE, safeOperation, null);
        }
        if (!beginModelInstall()) {
            return result(MatrixErrorCode.OVERLOADED, safeOperation, null);
        }
        try {
            modelDownloadExecutor.execute(() -> runModelInstall("离线模型安装"));
            return result(MatrixErrorCode.SUCCESS, safeOperation, null);
        } catch (RejectedExecutionException unavailable) {
            endModelInstall();
            return result(MatrixErrorCode.OVERLOADED, safeOperation, null);
        }
    }

    /**
     * ROM 预埋场景的启动期自动安装：Host 拉起后从系统预埋归档本地补齐当前引擎所需
     * 模型（预埋归档经钉死 SHA-256 准入，见 SherpaModelDownloader）。无预埋的普通
     * 安装不触发，行为不变。安装只依赖文件系统——进度入库失败仅降级显示，不查
     * persistence gate，SQLCipher 降级时语音模型照常就绪。
     */
    public void schedulePresetAutoInstall() {
        if (!sherpaDownloader.hasPresetArchive()) return;
        if (!beginModelInstall()) return;
        try {
            modelDownloadExecutor.execute(() -> runModelInstall("预埋模型自动安装"));
        } catch (RejectedExecutionException unavailable) {
            endModelInstall();
        }
    }

    /** 单飞占位；true = 获得安装槽位，调用方结束前须 {@link #endModelInstall()}。 */
    private boolean beginModelInstall() {
        synchronized (modelInstallLock) {
            if (modelInstallInFlight) return false;
            modelInstallInFlight = true;
            return true;
        }
    }

    private void endModelInstall() {
        synchronized (modelInstallLock) { modelInstallInFlight = false; }
    }

    /** 安装任务体：吞掉一切失败只留日志（executor 任务无 Future，异常会静默丢失）。 */
    private void runModelInstall(String what) {
        try {
            performOfflineModelInstall();
        } catch (IOException failure) {
            android.util.Log.w("MatrixAgent", "[Voice] " + what + "未完成: "
                    + failure.getClass().getSimpleName());
        } catch (RuntimeException failure) {
            android.util.Log.w("MatrixAgent", "[Voice] " + what + "异常: ", failure);
        } finally {
            endModelInstall();
        }
    }

    /** 按当前引擎补齐离线模型并刷新空闲 TTS 路由。 */
    private void performOfflineModelInstall() throws IOException {
        if (enginePreference.isSherpaSelected()) {
            installIfMissing(sherpaAsr);
            installIfMissing(sherpaVad);
            installIfMissing(sherpaKws);
            installIfMissing(sherpaTts);
        } else {
            installIfMissing(englishModel);
            installIfMissing(chineseModel);
            installIfMissing(sherpaTts);
        }
        // An already-built runtime may be holding the system-TTS fallback selected
        // before the local model was installed. Rebuild it while idle so the very
        // next PTT/wake uses Piper rather than honestly failing output again.
        recreateIdleRuntimeForInstalledTts();
    }

    @Override public VoiceOperationResult deleteOfflineModel(String modelId, String operationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        // The Vosk native model can retain file mappings after a session has ended.  Never unlink
        // a loaded mapping: users can delete it safely before first use or after a Host restart.
        if (currentSessionId != null || (runtime != null
                && (runtime.isSessionActive() || runtime.hasLoadedModels()))) {
            return result(MatrixErrorCode.UNSUPPORTED_OPERATION, safeOperation, null);
        }
        try {
            if (sherpaTts.name.equals(modelId)) {
                sherpaDownloader.delete(sherpaTts);
            } else if (enginePreference.isSherpaSelected()) {
                sherpaDownloader.delete(sherpaSpecFor(modelId));
            } else {
                modelDownloader.delete(voskSpecFor(modelId));
            }
            return result(MatrixErrorCode.SUCCESS, safeOperation, null);
        } catch (IllegalArgumentException unknown) {
            throw unknown; // 未知模型 id 属入参错误，走 Binder 层拒绝
        } catch (IOException e) {
            String message = e.getMessage();
            int code = message != null && message.startsWith("MODEL_BUSY")
                    ? MatrixErrorCode.OVERLOADED : MatrixErrorCode.TASK_FAILED;
            return result(code, safeOperation, null);
        }
    }

    @Override public String getAsrEngine() {
        callerResolver.caller();
        return enginePreference.getEngine().name();
    }

    @Override public VoiceOperationResult setAsrEngine(String engine, String operationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        AsrEngineSelection selection;
        try {
            selection = AsrEngineSelection.valueOf(String.valueOf(engine));
        } catch (IllegalArgumentException invalid) {
            return result(MatrixErrorCode.INVALID_ARGUMENT, safeOperation, null);
        }
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (currentSessionId != null || (runtime != null && runtime.isSessionActive())) {
            return result(MatrixErrorCode.INVALID_STATE, safeOperation, currentSessionId);
        }
        enginePreference.setEngine(selection);
        // 空闲期热切换：回收当前 Runtime（含已加载模型），下次语音使用按新引擎懒重建。
        // shutdownIfOwned 是进程唯一 Owner 的受控回收路径（holder 注销 + native 释放）。
        if (runtime != null) {
            SystemVoiceRuntimeOwner.shared(application).shutdownIfOwned();
        }
        Log.i("MatrixAgent", "[Voice] ASR 引擎切换 → " + selection.name());
        dispatchStatus();
        return result(MatrixErrorCode.SUCCESS, safeOperation, null);
    }

    @Override public TencentTtsConfig getTencentTtsConfig() {
        callerResolver.caller();
        return tencentTtsConfig.projection();
    }

    @Override public VoiceOperationResult provisionTencentTts(TencentTtsProvisionInput input,
            ParcelFileDescriptor credentialPipe, String operationId,
            IVoiceTtsConfigCallback callback) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        if (!persistenceGate.isAvailable()) {
            closeQuietly(credentialPipe);
            notifyTencentConfig(callback, safeOperation, MatrixErrorCode.PERSISTENCE_UNAVAILABLE);
            return result(MatrixErrorCode.PERSISTENCE_UNAVAILABLE, safeOperation, null);
        }
        if (credentialPipe == null || input == null
                || input.schemaVersion > ParcelSchema.CURRENT) {
            closeQuietly(credentialPipe);
            notifyTencentConfig(callback, safeOperation, MatrixErrorCode.INVALID_ARGUMENT);
            return result(MatrixErrorCode.INVALID_ARGUMENT, safeOperation, null);
        }
        try {
            SecureTencentTtsConfigStore.validateOptions(input.voiceType, input.emotionCategory,
                    input.emotionIntensity);
        } catch (IllegalArgumentException invalid) {
            closeQuietly(credentialPipe);
            notifyTencentConfig(callback, safeOperation, MatrixErrorCode.INVALID_ARGUMENT);
            return result(MatrixErrorCode.INVALID_ARGUMENT, safeOperation, null);
        }
        try {
            modelDownloadExecutor.execute(() -> {
                byte[][] parts = null;
                int code = MatrixErrorCode.SUCCESS;
                try {
                    parts = readTencentCredential(credentialPipe);
                    tencentTtsConfig.save(parts[0], parts[1], input.voiceType,
                            input.emotionCategory, input.emotionIntensity);
                    recreateIdleRuntimeForTencentTtsChange();
                    notifyTtsOutputRouteChanged();
                    Log.i("MatrixAgent", "[TencentTts] credential provisioning complete voiceType="
                            + input.voiceType + " emotion=" + input.emotionCategory);
                } catch (Exception error) {
                    code = MatrixErrorCode.INVALID_ARGUMENT;
                    Log.w("MatrixAgent", "[TencentTts] credential provisioning rejected type="
                            + error.getClass().getSimpleName());
                } finally {
                    if (parts != null) {
                        java.util.Arrays.fill(parts[0], (byte) 0);
                        java.util.Arrays.fill(parts[1], (byte) 0);
                    }
                    closeQuietly(credentialPipe);
                }
                notifyTencentConfig(callback, safeOperation, code);
            });
            return result(MatrixErrorCode.SUCCESS, safeOperation, null);
        } catch (RejectedExecutionException full) {
            closeQuietly(credentialPipe);
            notifyTencentConfig(callback, safeOperation, MatrixErrorCode.OVERLOADED);
            return result(MatrixErrorCode.OVERLOADED, safeOperation, null);
        }
    }

    @Override public VoiceOperationResult clearTencentTts(String operationId) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        if (currentSessionId != null) return result(MatrixErrorCode.INVALID_STATE, safeOperation,
                currentSessionId);
        try {
            tencentTtsConfig.clear();
            recreateIdleRuntimeForTencentTtsChange();
            notifyTtsOutputRouteChanged();
            Log.i("MatrixAgent", "[TencentTts] credential cleared");
            return result(MatrixErrorCode.SUCCESS, safeOperation, null);
        } catch (Exception e) {
            Log.w("MatrixAgent", "[TencentTts] credential clear failed type="
                    + e.getClass().getSimpleName());
            return result(MatrixErrorCode.TASK_FAILED, safeOperation, null);
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
        // languageTag 是可选提示；ASR/系统 locale 会在 null 时选择默认语言，不能让 SDK
        // 文档允许的 null 请求在 Host 被拒绝。
        if (request.languageTag != null) HostInputValidator.requireLanguageTag(request.languageTag);
    }

    private VoskModelSpec voskSpecFor(String modelId) {
        if (englishModel.name.equals(modelId)) return englishModel;
        if (chineseModel.name.equals(modelId)) return chineseModel;
        throw new IllegalArgumentException("unknown offline voice model");
    }

    private SherpaModelSpec sherpaSpecFor(String modelId) {
        if (sherpaAsr.name.equals(modelId)) return sherpaAsr;
        if (sherpaVad.name.equals(modelId)) return sherpaVad;
        if (sherpaKws.name.equals(modelId)) return sherpaKws;
        if (sherpaTts.name.equals(modelId)) return sherpaTts;
        throw new IllegalArgumentException("unknown offline voice model");
    }

    private void installIfMissing(VoskModelSpec model) throws IOException {
        boolean ready = modelDownloader.isDownloaded(model);
        Log.i("MatrixAgent", "[VoiceModel] install request name=" + model.name + " ready=" + ready);
        if (!ready) modelDownloader.download(model, () -> false);
    }

    private void installIfMissing(SherpaModelSpec model) throws IOException {
        boolean ready = sherpaDownloader.isDownloaded(model);
        Log.i("MatrixAgent", "[VoiceModel] install request name=" + model.name + " ready=" + ready);
        if (!ready) sherpaDownloader.download(model, () -> false);
    }

    /** Re-resolve TTS only at a proven idle boundary; never cuts an active utterance/session. */
    private void recreateIdleRuntimeForInstalledTts() {
        if (!sherpaDownloader.isDownloaded(sherpaTts)) return;
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (currentSessionId != null || (runtime != null && runtime.isSessionActive())) return;
        if (runtime != null) {
            Log.i("MatrixAgent", "[Voice] 本地 Piper 已安装，回收空闲 Runtime 以切换播报引擎");
            SystemVoiceRuntimeOwner.shared(application).shutdownIfOwned();
        }
    }

    /** A cloud configuration change is observed only when a fresh TTS port is assembled. */
    private void recreateIdleRuntimeForTencentTtsChange() {
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (currentSessionId != null || (runtime != null && runtime.isSessionActive())) return;
        if (runtime != null) {
            Log.i("MatrixAgent", "[TencentTts] 回收空闲 Runtime 以应用新的播报路由");
            SystemVoiceRuntimeOwner.shared(application).shutdownIfOwned();
        }
    }

    private void notifyTtsOutputRouteChanged() {
        try {
            ttsOutputRouteChangedListener.run();
        } catch (RuntimeException failure) {
            Log.w("MatrixAgent", "[TencentTts] readback 路由刷新失败 type="
                    + failure.getClass().getSimpleName());
        }
    }

    /** Framed credential wire format: four-byte big-endian SecretId length, then id and key. */
    private static byte[][] readTencentCredential(ParcelFileDescriptor descriptor) throws IOException {
        final int maxBytes = 1024;
        try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor());
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[256];
            for (int count; (count = input.read(chunk)) != -1;) {
                if (out.size() + count > maxBytes) throw new IOException("credential too large");
                out.write(chunk, 0, count);
            }
            byte[] packed = out.toByteArray();
            try {
                if (packed.length < Integer.BYTES + 2) throw new IOException("credential truncated");
                int idSize = ByteBuffer.wrap(packed, 0, Integer.BYTES).getInt();
                int keySize = packed.length - Integer.BYTES - idSize;
                if (idSize <= 0 || keySize <= 0 || idSize > 256 || keySize > 512) {
                    throw new IOException("credential frame invalid");
                }
                return new byte[][] {
                        java.util.Arrays.copyOfRange(packed, Integer.BYTES, Integer.BYTES + idSize),
                        java.util.Arrays.copyOfRange(packed, Integer.BYTES + idSize, packed.length)
                };
            } finally {
                java.util.Arrays.fill(packed, (byte) 0);
            }
        }
    }

    private static void notifyTencentConfig(IVoiceTtsConfigCallback callback, String operation,
            int code) {
        if (callback == null) return;
        try { callback.onTencentTtsConfigured(operation, code); }
        catch (RemoteException ignored) { }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor == null) return;
        try { descriptor.close(); } catch (IOException ignored) { }
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
                finishSession(sessionId, publicState, MatrixErrorCode.SUCCESS);
            }
        }

        @Override public void onError(String code) {
            if (!isCurrent(sessionId)) return;
            finishSession(sessionId, VoiceServiceStatus.SESSION_IDLE,
                    publicVoiceFailureCode(code));
        }
    }

    /** Maps an internal voice-stage failure to a stable, user-actionable public error. */
    static int publicVoiceFailureCode(String code) {
        return "TTS_INIT_FAILED".equals(code)
                || "TTS_LANG_UNAVAILABLE".equals(code)
                || "TTS_NOT_READY".equals(code)
                || "TTS_READY_TIMEOUT".equals(code)
                || (code != null && code.startsWith("TTS_"))
                ? MatrixErrorCode.VOICE_OUTPUT_UNAVAILABLE : MatrixErrorCode.TASK_FAILED;
    }

    private boolean isCurrent(String sessionId) {
        return sessionId.equals(currentSessionId);
    }

    private void beginListening(VoiceRuntime runtime, String sessionId) {
        if (!isCurrent(sessionId) || !enabled) return;
        Log.i("MatrixAgent", "[VoiceSession] runtime ready, dispatching PTT listen");
        runtime.resume();
        runtime.manualWake(com.matrix.agent.voice.VoiceSessionController.WAKE_SOURCE_BINDER_PTT);
        dispatchSessionState(sessionId, VoiceServiceStatus.SESSION_LISTENING);
        dispatchStatus();
    }

    private void failStartup(String sessionId) {
        if (isCurrent(sessionId)) {
            Log.e("MatrixAgent", "[VoiceSession] startup failed, closing PTT session");
            finishSession(sessionId, VoiceServiceStatus.SESSION_IDLE, MatrixErrorCode.TASK_FAILED);
        }
    }

    private void dispatchSessionState(String sessionId, int state) {
        sessionCallbacks.dispatch(callback -> callback.onSessionStateChanged(sessionId, state));
    }

    private void finishSession(int finalState, int errorCode) {
        finishSession(null, finalState, errorCode);
    }

    /**
     * Ends the active session, optionally only when it is still the session that initiated
     * this callback.  Runtime startup and listener callbacks are asynchronous: without the
     * expected-id check, a delayed callback from an old PTT gesture could clear a newer one.
     */
    private void finishSession(String expectedSessionId, int finalState, int errorCode) {
        synchronized (sessionLock) {
            String session = currentSessionId;
            if (session == null || (expectedSessionId != null
                    && !expectedSessionId.equals(session))) return;
            currentSessionId = null;
            VoiceRuntime runtime = VoiceRuntimeHolder.get();
            if (runtime != null) {
                runtime.setUiListener(null);
                runtime.clearVoiceSessionContext(session);
            }
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

    /** 对话域装配后注入绑定存储（可选）。 */
    public void setBindingStore(
            com.matrix.agent.conversation.ConversationVoiceBindingStore store) {
        this.bindingStore = store;
    }

    /** 对话图注册一次；每次冷重建/引擎切换后由 startUserInitiatedSession 重放到新 runtime。 */
    public void setControllerConfigurer(
            Consumer<com.matrix.agent.voice.VoiceSessionController> configurer) {
        this.controllerConfigurer = configurer;
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (runtime != null && configurer != null) runtime.addControllerConfigurer(configurer);
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
