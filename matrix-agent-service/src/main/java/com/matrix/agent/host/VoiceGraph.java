package com.matrix.agent.host;

import android.app.Application;
import android.os.IBinder;

import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.VoiceRuntimeHolder;
import com.matrix.agent.voice.system.VoiceRuntimeBootstrap;

/** Voice domain boundary; PCM and runtime implementation never cross this graph. */
final class VoiceGraph {
    private final VoiceServiceStub service;

    VoiceGraph(Application application, PersistenceGate persistence,
            ModelServiceStub.CallerResolver callers) {
        bootstrapRuntime(application);
        service = new VoiceServiceStub(persistence, callers);
    }

    IBinder binder() { return service.asBinder(); }
    boolean isAvailable() { return VoiceRuntimeHolder.get() != null; }
    void shutdown() { service.shutdown(); }

    /**
     * Create only the lightweight owner. Model download, Vosk assembly and microphone access
     * remain deferred until the user-initiated PTT request has passed permission checks.
     */
    private static void bootstrapRuntime(Application application) {
        if (VoiceRuntimeHolder.get() != null) return;
        VoiceRuntime runtime = VoiceRuntimeBootstrap.getOrCreate(application);
        if (runtime == null) throw new IllegalStateException("voice bootstrap returned null runtime");
    }
}
