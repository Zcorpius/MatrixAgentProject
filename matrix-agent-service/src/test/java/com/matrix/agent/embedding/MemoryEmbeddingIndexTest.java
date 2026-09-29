package com.matrix.agent.embedding;

import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.identity.VehicleZone;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class MemoryEmbeddingIndexTest {
    private static final MemoryScope OWNER = new MemoryScope("owner", VehicleZone.DRIVER);
    @Test public void updatedDeletedAndResetSourcesCannotReceiveStaleInference() {
        for (String mutation : List.of("update", "delete", "reset")) {
            Store store = new Store();
            store.put(OWNER, "fact.topic", "old", 1);
            MemoryEmbeddingIndex index = new MemoryEmbeddingIndex(store, encoder(() -> {
                switch (mutation) {
                    case "update" -> store.put(OWNER, "fact.topic", "new", 2);
                    case "delete" -> store.rows.clear();
                    case "reset" -> { store.rows.clear(); store.epoch++; }
                }
            }), Runnable::run, .65);
            index.scheduleBackfill(OWNER, 0);
            assertTrue(mutation, store.vectors.isEmpty());
        }
    }
    @Test public void scopeVersionAndThresholdAreEnforcedAndInvalidVectorsNeverCommit() {
        Store store = new Store();
        store.put(OWNER, "fact.topic", "A", 1);
        store.put(new MemoryScope("other", VehicleZone.DRIVER), "fact.topic", "B", 1);
        store.put(new MemoryScope("owner", VehicleZone.PASSENGER), "fact.topic", "C", 1);
        var index = new MemoryEmbeddingIndex(store, encoder(() -> { }), Runnable::run, .65);
        index.scheduleBackfill(OWNER, 0);
        assertEquals(1, store.vectors.size());
        assertEquals("fact.topic", index.recall(OWNER, "same").get(0).key());
        assertTrue(index.recall(OWNER, "unrelated").isEmpty());
        assertTrue(index.recall(new MemoryScope("absent", VehicleZone.DRIVER), "same").isEmpty());
        store.vectors.clear();
        var invalid = new MemoryEmbeddingIndex(store, new MemoryEmbeddingIndex.Encoder() {
            public String modelVersion() { return "v1"; }
            public int dimension() { return 2; }
            public float[] encode(String t, boolean q, java.util.function.BooleanSupplier c) { return new float[]{Float.NaN, 0}; }
        }, Runnable::run, .65);
        invalid.scheduleBackfill(OWNER, 0);
        assertTrue(store.vectors.isEmpty());
        store.vectors.add(new MemoryVectorStore.Indexed(store.rows.get(0), "old-model", new float[]{1,0}));
        assertTrue(store.load(OWNER, 0, "v1", 2).isEmpty());
    }
    @Test public void backfillIsBoundedDeduplicatedAndClosedWorkCannotRun() {
        Store store = new Store();
        for (int i = 0; i < 100; i++) store.put(OWNER, "fact.item" + i, "A", i);
        List<Runnable> queue = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        var index = new MemoryEmbeddingIndex(store, encoder(calls::incrementAndGet), queue::add, .65);
        index.scheduleBackfill(OWNER, 0);
        index.scheduleBackfill(OWNER, 0);
        assertEquals(1, queue.size());
        queue.remove(0).run();
        assertEquals(64, calls.get());
        index.scheduleBackfill(OWNER, 0);
        index.close();
        queue.remove(0).run();
        assertEquals(64, calls.get());
        assertTrue(index.recall(OWNER, "same").isEmpty());
    }
    @Test public void vectorEncodingRejectsShapeNonfiniteAndZero() {
        assertArrayEquals(new float[]{.6f,.8f}, VectorMath.decode(VectorMath.encode(new float[]{3,4}), 2), .00001f);
        assertThrows(IllegalArgumentException.class, () -> VectorMath.normalized(new float[]{0,0}, 2));
        assertThrows(IllegalArgumentException.class, () -> VectorMath.normalized(new float[]{Float.POSITIVE_INFINITY}, 1));
        assertThrows(IllegalArgumentException.class, () -> VectorMath.decode(new byte[4], 2));
    }
    private static MemoryEmbeddingIndex.Encoder encoder(Runnable beforeResult) {
        return new MemoryEmbeddingIndex.Encoder() {
            public String modelVersion() { return "v1"; }
            public int dimension() { return 2; }
            public float[] encode(String text, boolean query, java.util.function.BooleanSupplier cancelled) {
                beforeResult.run();
                return query && text.equals("unrelated") ? new float[]{0,1} : new float[]{1,0};
            }
        };
    }
    private static final class Store implements MemoryVectorStore {
        long epoch;
        final List<Source> rows = new ArrayList<>();
        final List<Indexed> vectors = new ArrayList<>();
        void put(MemoryScope scope, String key, String value, long time) {
            rows.removeIf(row -> row.scope().equals(scope) && row.key().equals(key));
            rows.add(new Source(scope, key, value, time, epoch));
        }
        public long currentEpoch() { return epoch; }
        public List<Source> sources(MemoryScope scope, long requestedEpoch) {
            return requestedEpoch != epoch ? List.of() : rows.stream().filter(row -> row.scope().equals(scope)).toList();
        }
        public List<Indexed> load(MemoryScope scope, long e, String version, int dimension) {
            return vectors.stream().filter(row -> row.source().scope().equals(scope) && row.source().epoch() == e
                    && row.modelVersion().equals(version) && row.vector().length == dimension
                    && rows.contains(row.source())).toList();
        }
        public boolean commit(Source source, String version, float[] vector) {
            if (epoch != source.epoch() || !rows.contains(source)) return false;
            vectors.add(new Indexed(source, version, vector));
            return true;
        }
    }
}
