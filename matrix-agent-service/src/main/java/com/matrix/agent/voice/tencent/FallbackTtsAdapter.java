package com.matrix.agent.voice.tencent;

import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.port.ManagedTtsPort;

import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;

/**
 * Request-level fallback: a Tencent failure never reaches the session controller until the
 * local engine has also failed. This preserves one TTS authority and avoids a false
 * {@code VOICE_OUTPUT_UNAVAILABLE} merely because the network is transiently unavailable.
 */
public final class FallbackTtsAdapter implements ManagedTtsPort {
    private final ManagedTtsPort primary;
    private final ManagedTtsPort fallback;
    private final Object lock = new Object();
    private final Set<String> retrying = new HashSet<>();
    private final Map<String, SpeakableResponse> pending = new HashMap<>();
    private volatile Listener listener;
    private volatile boolean closed;

    public FallbackTtsAdapter(ManagedTtsPort primary, ManagedTtsPort fallback) {
        this.primary = primary;
        this.fallback = fallback;
        primary.setListener(new TtsListener(true));
        fallback.setListener(new TtsListener(false));
    }

    @Override public void setListener(Listener listener) {
        this.listener = listener;
        if (!closed && listener != null) listener.onReady();
    }
    @Override public void speak(SpeakableResponse response, String utteranceId) {
        if (closed) { notifyError(utteranceId, "TTS_CLOSED"); return; }
        synchronized (lock) { retrying.remove(utteranceId); pending.put(utteranceId, response); }
        primary.speak(response, utteranceId);
    }
    @Override public void stop() { primary.stop(); fallback.stop(); synchronized (lock) { retrying.clear(); pending.clear(); } }
    @Override public void shutdown() { closed = true; stop(); listener = null; primary.shutdown(); fallback.shutdown(); }

    private final class TtsListener implements Listener {
        private final boolean fromPrimary;
        TtsListener(boolean fromPrimary) { this.fromPrimary = fromPrimary; }
        @Override public void onDone(String id) {
            if (!closed) {
                synchronized (lock) { retrying.remove(id); pending.remove(id); }
                notifyDone(id);
            }
        }
        @Override public void onError(String id, String code) {
            if (closed) return;
            if (fromPrimary) {
                SpeakableResponse response;
                synchronized (lock) {
                    if (!retrying.add(id)) return;
                    response = pending.get(id);
                }
                if (response == null) { notifyError(id, code); return; }
                fallback.speak(response, id);
            } else {
                synchronized (lock) { retrying.remove(id); pending.remove(id); }
                notifyError(id, code);
            }
        }
        @Override public void onReady() { }
        @Override public void onInitError(String code) { }
    }
    private void notifyDone(String id) { Listener l = listener; if (l != null) l.onDone(id); }
    private void notifyError(String id, String code) { Listener l = listener; if (l != null) l.onError(id, code); }
}
