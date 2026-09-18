package com.matrix.agent.voice.system;

import android.app.Application;

import com.matrix.agent.voice.VoiceRuntime;

/** Release Host bridge that constructs (but never starts) the Vosk runtime. */
public final class VoiceRuntimeBootstrap {
    private VoiceRuntimeBootstrap() { }

    public static VoiceRuntime getOrCreate(Application application) {
        return SystemVoiceRuntimeOwner.shared(application).getOrCreate();
    }
}
