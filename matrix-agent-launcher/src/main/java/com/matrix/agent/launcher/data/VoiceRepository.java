package com.matrix.agent.launcher.data;

import androidx.annotation.NonNull;

import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;
import com.matrix.agent.api.voice.TencentTtsConfig;
import com.matrix.agent.api.voice.TencentTtsProvisionInput;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.client.VoiceManager;
import com.matrix.agent.client.VoiceSessionListener;

import java.util.function.Consumer;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** SDK-backed voice data source.  Voice text is a callback payload; audio never leaves Host. */
public final class VoiceRepository {
    private final LauncherHostGateway gateway;

    public VoiceRepository(LauncherHostGateway gateway) { this.gateway = gateway; }

    public boolean isHostConnected() { return gateway.isConnected(); }

    public void status(@NonNull Consumer<LauncherHostGateway.Result<VoiceServiceStatus>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.getStatus();
        }, receiver);
    }

    public void start(@NonNull VoiceSessionRequest request, @NonNull String operationId,
            @NonNull SessionListener listener,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceSessionHandle>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null
                    : manager.startUserInitiatedSession(request, operationId, new VoiceSessionListener() {
                        @Override public void onSessionStateChanged(String sessionId, int state) {
                            listener.onSessionStateChanged(sessionId, state);
                        }
                        @Override public void onPartialText(String sessionId, String text) {
                            listener.onPartialText(sessionId, text);
                        }
                        @Override public void onFinalText(String sessionId, String text) {
                            listener.onFinalText(sessionId, text);
                        }
                        @Override public void onSessionError(String sessionId, int errorCode) {
                            listener.onSessionError(sessionId, errorCode);
                        }
                    });
        }, receiver);
    }

    public void stop(@NonNull String sessionId, @NonNull String operationId,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.stopSession(sessionId, operationId);
        }, receiver);
    }

    /** User completes PTT: flush ASR final instead of cancelling it. */
    public void finish(@NonNull String sessionId, @NonNull String operationId,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.finishSession(sessionId, operationId);
        }, receiver);
    }

    public void interrupt(@NonNull String operationId,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.cancelCurrentSession(operationId);
        }, receiver);
    }

    public void offlineModels(@NonNull Consumer<LauncherHostGateway.Result<List<ModelDownloadInfo>>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.listOfflineModels();
        }, receiver);
    }

    public void deleteOfflineModel(@NonNull String modelId, @NonNull String operationId,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.deleteOfflineModel(modelId, operationId);
        }, receiver);
    }

    public void installOfflineModels(@NonNull String operationId,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.installOfflineModels(operationId);
        }, receiver);
    }

    /** 当前 ASR 引擎名（"VOSK" / "SHERPA"）；Host 未连接时回调 null。 */
    public void asrEngine(@NonNull Consumer<LauncherHostGateway.Result<String>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.getAsrEngine();
        }, receiver);
    }

    public void setAsrEngine(@NonNull String engine, @NonNull String operationId,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.setAsrEngine(engine, operationId);
        }, receiver);
    }

    public void tencentTtsConfig(@NonNull Consumer<LauncherHostGateway.Result<TencentTtsConfig>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.getTencentTtsConfig();
        }, receiver);
    }

    public void provisionTencentTts(@NonNull TencentTtsProvisionInput input, @NonNull char[] secretId,
            @NonNull char[] secretKey, @NonNull String operationId,
            @NonNull VoiceManager.TencentTtsConfigListener listener,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.provisionTencentTts(input, secretId, secretKey,
                    operationId, listener);
        }, receiver);
    }

    public void clearTencentTts(@NonNull String operationId,
            @NonNull Consumer<LauncherHostGateway.Result<VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.clearTencentTts(operationId);
        }, receiver);
    }

    /** A small, lifecycle-owned poll: downloader checkpoints are persisted by the Host. */
    public ScheduledFuture<?> scheduleModelRefresh(@NonNull Runnable task) {
        return gateway.scheduleWithFixedDelay(task, 1, TimeUnit.SECONDS);
    }

    public void subscribeStatus(@NonNull StatusListener listener,
            @NonNull Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver) {
        gateway.execute(agent -> {
            VoiceManager manager = agent.getVoiceManager();
            return manager == null ? null : manager.subscribeStatus(listener::onStatusChanged);
        }, receiver);
    }

    /** Launcher-owned callback port; presentation stays independent of the SDK listener type. */
    public interface SessionListener {
        void onSessionStateChanged(String sessionId, int state);
        void onPartialText(String sessionId, String text);
        void onFinalText(String sessionId, String text);
        void onSessionError(String sessionId, int errorCode);
    }

    /** Launcher-owned status port; no SDK listener type crosses into presentation. */
    public interface StatusListener {
        void onStatusChanged(VoiceServiceStatus status);
    }
}
