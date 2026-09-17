package com.matrix.agent.voice.system;

import android.app.Application;

import com.matrix.agent.voice.VoiceRuntime;

/** Debug-only bridge used by the Host Binder to construct (but not start) the Vosk runtime. */
public final class VoiceRuntimeBootstrap {
    private VoiceRuntimeBootstrap() { }

    public static VoiceRuntime getOrCreate(Application application) {
        return SystemVoiceRuntimeOwner.shared(application).getOrCreate();
    }
}
