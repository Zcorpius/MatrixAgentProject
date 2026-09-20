package com.matrix.agent.voice.port;

/**
 * A {@link TtsPort} whose native/platform resources have an explicit lifecycle.
 *
 * <p>The session controller deliberately depends only on {@link TtsPort}: it owns speech
 * state but not the concrete engine.  An assembly owns this narrower extension and invokes
 * {@link #shutdown()} after the controller has stopped accepting callbacks.  This keeps an
 * Android system engine and an app-owned on-device engine interchangeable without hiding a
 * native release behind {@code finalize()}.</p>
 */
public interface ManagedTtsPort extends TtsPort {
    /** Stop pending work and release engine resources. Idempotent. */
    void shutdown();
}
