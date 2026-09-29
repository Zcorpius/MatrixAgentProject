package com.matrix.agent.embedding;

import com.matrix.agent.data.memory.MemoryScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Values are consumed only by local inference. This port does not change memory save admission. */
public interface MemoryVectorStore {
    record Source(MemoryScope scope, String key, String value, long capturedAt, long epoch) {
        public Source {
            java.util.Objects.requireNonNull(scope);
            java.util.Objects.requireNonNull(key);
            value = value == null ? "" : value;
        }
        public String digest() {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                        (key.length() + ":" + key + value.length() + ":" + value + ":" + capturedAt)
                                .getBytes(StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder(64);
                for (byte b : hash) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                return out.toString();
            } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        public String encodingText() { return key + "\n" + value; }
    }
    record Indexed(Source source, String modelVersion, float[] vector) {
        public Indexed { vector = vector.clone(); }
        @Override public float[] vector() { return vector.clone(); }
    }
    long currentEpoch();
    List<Source> sources(MemoryScope scope, long epoch);
    List<Indexed> load(MemoryScope scope, long epoch, String modelVersion, int dimension);
    /** Atomically compare scope, epoch and source content/version before inserting derived data. */
    boolean commit(Source source, String modelVersion, float[] vector);
}
