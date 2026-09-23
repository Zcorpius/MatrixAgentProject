package com.matrix.agent.host.di;

import com.matrix.agent.host.rpc.ModelServiceStub;
import com.matrix.agent.host.rpc.VoiceServiceStub;
import com.matrix.agent.task.durable.PersistenceGate;
import com.matrix.agent.data.db.ModelDownloadDao;

import android.app.Application;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.VoiceRuntimeHolder;
import com.matrix.agent.voice.VoiceSessionController;
import com.matrix.agent.voice.system.VoiceRuntimeBootstrap;

/** Voice domain boundary; PCM and runtime implementation never cross this graph. */
final class VoiceGraph {
    private final VoiceServiceStub service;

    VoiceGraph(Application application, ModelDownloadDao downloads, ExecutorService modelDownloads,
            PersistenceGate persistence,
            ModelServiceStub.CallerResolver callers) {
        bootstrapRuntime(application);
        service = new VoiceServiceStub(application, downloads, modelDownloads, persistence, callers);
        // ROM 预埋归档存在时启动即本地安装（幂等；无预埋则不触发）
        service.schedulePresetAutoInstall();
    }

    IBinder binder() { return service.asBinder(); }
    boolean isAvailable() { return VoiceRuntimeHolder.get() != null; }
    void shutdown() { service.shutdown(); }
    void setBindingStore(com.matrix.agent.conversation.ConversationVoiceBindingStore store) {
        service.setBindingStore(store);
    }
    void addControllerConfigurer(Consumer<VoiceSessionController> configurer) {
        service.setControllerConfigurer(configurer);
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        if (runtime != null) runtime.addControllerConfigurer(configurer);
    }
    void setTtsOutputRouteChangedListener(Runnable listener) {
        service.setTtsOutputRouteChangedListener(listener);
    }

    /**
     * Create only the lightweight owner. Model download, Vosk assembly and microphone access
     * remain deferred until the user-initiated PTT request has passed permission checks.
     */
    private static void bootstrapRuntime(Application application) {
        if (VoiceRuntimeHolder.get() != null) return;
        if (VoiceRuntimeBootstrap.getOrCreate(application) == null) {
            throw new IllegalStateException("voice bootstrap returned null runtime");
        }
    }
}
