package com.matrix.agent.ondevice;

import java.util.function.BooleanSupplier;

/** Local embedding contract; contains no Host persistence, authorization or Android UI dependency. */
public interface OnDeviceEmbedder extends AutoCloseable {
    record Profile(String modelVersion, int dimension, int maxTokens, String pooling) {
        public Profile {
            if (modelVersion == null || modelVersion.isBlank() || dimension < 1 || dimension > 4096
                    || maxTokens < 2 || maxTokens > 512 || !"CLS_L2".equals(pooling)) {
                throw new IllegalArgumentException("unsupported embedding profile");
            }
        }
    }
    Profile profile();
    float[] encode(String text, BooleanSupplier cancelled);
    @Override void close();
}
