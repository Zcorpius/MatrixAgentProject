package com.matrix.agent.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Canonical little-endian, unit-length vectors; corrupt data never participates in ranking. */
public final class VectorMath {
    private VectorMath() { }
    public static float[] normalized(float[] values, int dimension) {
        if (values == null || values.length != dimension || dimension < 1 || dimension > 4096) {
            throw new IllegalArgumentException("vector dimension");
        }
        double squared = 0;
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("nonfinite vector");
            squared += (double) value * value;
        }
        if (squared < 1e-12) throw new IllegalArgumentException("zero vector");
        float[] result = values.clone();
        double norm = Math.sqrt(squared);
        for (int i = 0; i < dimension; i++) result[i] /= norm;
        return result;
    }
    public static byte[] encode(float[] vector) {
        float[] normalized = normalized(vector, vector.length);
        ByteBuffer bytes = ByteBuffer.allocate(normalized.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : normalized) bytes.putFloat(value);
        return bytes.array();
    }
    public static float[] decode(byte[] bytes, int dimension) {
        if (bytes == null || dimension < 1 || dimension > 4096 || bytes.length != dimension * Float.BYTES) {
            throw new IllegalArgumentException("vector encoding");
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) vector[i] = buffer.getFloat();
        return normalized(vector, dimension);
    }
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) throw new IllegalArgumentException("vector shape");
        double result = 0;
        for (int i = 0; i < a.length; i++) result += (double) a[i] * b[i];
        return Math.max(-1, Math.min(1, result));
    }
}
