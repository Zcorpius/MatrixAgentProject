package com.matrix.agent.ondevice.mnn;

import com.matrix.agent.ondevice.OnDeviceEmbedder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Session access/close serialize; cancellation discards results without destroying an in-flight native session. */
public final class MnnOnDeviceEmbedder implements OnDeviceEmbedder {
    private final Profile profile;
    private long handle;
    public MnnOnDeviceEmbedder(String verifiedConfigPath, Profile profile) {
        this.profile = java.util.Objects.requireNonNull(profile);
        MNNLibraryLoader.loadLibraries();
        handle = nativeLoad(verifiedConfigPath, profile.dimension());
        if (handle == 0) throw new IllegalStateException("embedding model unavailable");
    }
    @Override public Profile profile() { return profile; }
    @Override public synchronized float[] encode(String text, BooleanSupplier cancelled) {
        if (handle == 0) throw new IllegalStateException("embedding model closed");
        if (text == null || text.isBlank() || text.length() > 8192) throw new IllegalArgumentException("embedding text bound");
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        // JNI receives canonical UTF-8, not Modified UTF-8 (supplementary code points remain valid).
        float[] vector = nativeEncode(handle, text.getBytes(StandardCharsets.UTF_8), profile.maxTokens());
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (vector == null || vector.length != profile.dimension()) throw new IllegalStateException("embedding output shape");
        double squared = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("nonfinite embedding");
            squared += (double) value * value;
        }
        if (squared < 1e-12 || !Double.isFinite(squared)) throw new IllegalStateException("degenerate embedding");
        double norm = Math.sqrt(squared);
        for (int i = 0; i < vector.length; i++) vector[i] /= norm;
        return vector;
    }
    @Override public synchronized void close() {
        if (handle != 0) { nativeClose(handle); handle = 0; }
    }
    private static native long nativeLoad(String config, int dimension);
    private static native float[] nativeEncode(long handle, byte[] text, int maxTokens);
    private static native void nativeClose(long handle);
}
