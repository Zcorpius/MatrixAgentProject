package com.matrix.agent.embedding;

import com.matrix.agent.data.memory.MemoryScope;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Bounded derived index. Storage owns CAS; inference always happens outside storage transactions. */
public final class MemoryEmbeddingIndex implements SemanticVectorRecall, AutoCloseable {
    public interface Encoder {
        String modelVersion();
        int dimension();
        float[] encode(String text, boolean query, BooleanSupplier cancelled) throws Exception;
    }
    private static final int MAX_BACKFILL_BATCH = 64;
    private final MemoryVectorStore store;
    private final Encoder encoder;
    private final Executor backfillExecutor;
    private final double minimumCosine;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<MemoryScope> pending = new HashSet<>();

    public MemoryEmbeddingIndex(MemoryVectorStore store, Encoder encoder, Executor backfillExecutor,
            double minimumCosine) {
        if (!Double.isFinite(minimumCosine) || minimumCosine < 0 || minimumCosine > 1) {
            throw new IllegalArgumentException("cosine threshold");
        }
        this.store = store;
        this.encoder = encoder;
        this.backfillExecutor = backfillExecutor;
        this.minimumCosine = minimumCosine;
    }
    @Override public List<Hit> recall(MemoryScope scope, String query) {
        if (closed.get() || query == null || query.isBlank()) return List.of();
        try {
            long epoch = store.currentEpoch();
            List<MemoryVectorStore.Indexed> indexed = store.load(scope, epoch,
                    encoder.modelVersion(), encoder.dimension());
            scheduleBackfill(scope, epoch);
            if (indexed.isEmpty()) return List.of();
            float[] encoded = VectorMath.normalized(encoder.encode(query, true,
                    () -> closed.get() || Thread.currentThread().isInterrupted()), encoder.dimension());
            // Inference may overlap replacement/deletion. Revalidate source digests after the model lane returns.
            Set<String> currentDigests = new HashSet<>();
            for (var source : store.sources(scope, epoch)) currentDigests.add(source.digest());
            List<Hit> hits = new ArrayList<>();
            for (var row : indexed) {
                if (!currentDigests.contains(row.source().digest())) continue;
                double cosine = VectorMath.cosine(encoded, row.vector());
                if (cosine >= minimumCosine) hits.add(new Hit(row.source().key(), cosine));
            }
            if (closed.get() || store.currentEpoch() != epoch) return List.of();
            hits.sort(Comparator.comparingDouble(Hit::cosine).reversed().thenComparing(Hit::key));
            return List.copyOf(hits.subList(0, Math.min(20, hits.size())));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception unavailable) { return List.of(); }
    }
    public void scheduleBackfill(MemoryScope scope, long epoch) {
        synchronized (pending) {
            if (closed.get() || pending.size() >= 4 || !pending.add(scope)) return;
        }
        try {
            backfillExecutor.execute(() -> {
                boolean remaining = false;
                try { remaining = backfill(scope, epoch); }
                finally { synchronized (pending) { pending.remove(scope); } }
                if (remaining) scheduleBackfill(scope, epoch);
            });
        } catch (RuntimeException rejected) { synchronized (pending) { pending.remove(scope); } }
    }
    private boolean backfill(MemoryScope scope, long epoch) {
        try {
            Set<String> present = new HashSet<>();
            for (var row : store.load(scope, epoch, encoder.modelVersion(), encoder.dimension())) {
                present.add(row.source().digest());
            }
            int count = 0;
            for (var source : store.sources(scope, epoch)) {
                if (closed.get() || Thread.currentThread().isInterrupted() || store.currentEpoch() != epoch) return false;
                if (present.contains(source.digest())) continue;
                if (++count > MAX_BACKFILL_BATCH) return true;
                float[] vector = VectorMath.normalized(encoder.encode(source.encodingText(), false,
                        () -> closed.get() || Thread.currentThread().isInterrupted()), encoder.dimension());
                if (!closed.get()) store.commit(source, encoder.modelVersion(), vector);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception unavailable) { /* A later recall retries; no invalid/partial vector is committed. */ }
        return false;
    }
    @Override public void close() { closed.set(true); }
}
