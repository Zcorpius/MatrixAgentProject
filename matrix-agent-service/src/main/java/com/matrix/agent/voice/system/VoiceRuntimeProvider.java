package com.matrix.agent.voice.system;

import android.app.Application;

import com.matrix.agent.voice.VoiceRuntime;

/** Host-implemented factory boundary for the process-wide voice runtime. */
public interface VoiceRuntimeProvider {
    VoiceRuntime createVoiceRuntime(Application application);
}
