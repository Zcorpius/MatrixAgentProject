package com.matrix.agent.data.memory;

/**
 * Memory 召回的最小不可变单元。
 *
 * <p>{@code score} 不计算(默认 1.0),由 episodic / semantic 召回器填充
 * 相似度分(如 cosine similarity、BM25)。{@code capturedAtMillis} 与
 * {@code sourceSessionId} 用于调试,后续用于排序与去重。
 */
public final class MemorySnippet {
    private final MemoryLayer layer;
    private final MemoryScope scope;
    private final String key;
    private final String value;
    private final double score;
    private final long capturedAtMillis;
    private final String sourceSessionId;

    public MemorySnippet(MemoryLayer layer, MemoryScope scope, String key, String value,
            double score, long capturedAtMillis, String sourceSessionId) {
        if (layer == null) throw new IllegalArgumentException("layer 不能为空");
        if (scope == null) throw new IllegalArgumentException("scope 不能为空");
        if (key == null) throw new IllegalArgumentException("key 不能为空");
        this.layer = layer;
        this.scope = scope;
        this.key = key;
        this.value = value;
        this.score = score;
        this.capturedAtMillis = capturedAtMillis;
        this.sourceSessionId = sourceSessionId;
    }

    /** 简化工厂:score=1.0、capturedAt=0、sourceSession=null(默认召回)。 */
    public static MemorySnippet of(MemoryLayer layer, MemoryScope scope, String key, String value) {
        return new MemorySnippet(layer, scope, key, value, 1.0, 0L, null);
    }

    public MemoryLayer getLayer() { return layer; }
    public MemoryScope getScope() { return scope; }
    public String getKey() { return key; }
    public String getValue() { return value; }
    public double getScore() { return score; }
    public long getCapturedAtMillis() { return capturedAtMillis; }
    public String getSourceSessionId() { return sourceSessionId; }

    @Override
    public String toString() {
        return "MemorySnippet{layer=" + layer + ", keyLen=" + key.length()
                + ", score=" + score + "}";
    }
}
