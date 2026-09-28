package com.matrix.agent.host.di;

import android.content.Context;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.embedding.RoomMemoryVectorStore;
import com.matrix.agent.embedding.EmbeddingArtifact;
import com.matrix.agent.embedding.EmbeddingPreparation;
import com.matrix.agent.embedding.MemoryEmbeddingIndex;
import com.matrix.agent.embedding.SemanticVectorRecall;
import com.matrix.agent.ondevice.OnDeviceEmbedder;
import com.matrix.agent.ondevice.mnn.MnnOnDeviceEmbedder;
import com.matrix.agent.platform.MatrixExecutorRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Owns the optional embedding session; chat and embedding native work share the single MNN lane. */
public final class EmbeddingRuntimeGraph implements AutoCloseable {
    private final MatrixExecutorRegistry executors;
    private final EmbeddingArtifact artifact;
    private final EmbeddingPreparation preparation;
    private final MemoryEmbeddingIndex index;
    private final AtomicBoolean closed = new AtomicBoolean();
    // Accessed exclusively on modelExecutor, including retirement.
    private OnDeviceEmbedder embedder;
    private long retryAfterNanos;

    public EmbeddingRuntimeGraph(Context context, MatrixDatabase database, MatrixExecutorRegistry executors) {
        this.executors = executors;
        try (var in = context.getAssets().open("embedding/bge-small-zh-v1.5.json")) {
            artifact = new EmbeddingArtifact(new File(context.getFilesDir(), "models/embedding"),
                    readManifest(in));
        } catch (Exception invalid) { throw new IllegalStateException("invalid packaged embedding manifest", invalid); }
        preparation = new EmbeddingPreparation(artifact,
                name -> context.getAssets().open("embedding/bge-small-zh-v1.5/" + name),
                executors.embeddingInstallExecutor());
        index = database == null ? null : new MemoryEmbeddingIndex(new RoomMemoryVectorStore(database),
                new MemoryEmbeddingIndex.Encoder() {
                    @Override public String modelVersion() { return artifact.profile().modelVersion(); }
                    @Override public int dimension() { return artifact.profile().dimension(); }
                    @Override public float[] encode(String text, boolean query, BooleanSupplier cancelled) throws Exception {
                        return encodeOnModelLane(text, query, cancelled);
                    }
                }, executors.embeddingIndexExecutor(), 0.50);
    }
    private static String readManifest(java.io.InputStream input) throws java.io.IOException {
        var bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) != -1;) {
            if (bytes.size() + count > 16_384) throw new java.io.IOException("embedding manifest too large");
            bytes.write(buffer, 0, count);
        }
        return bytes.toString(StandardCharsets.UTF_8.name());
    }
    public EmbeddingArtifact artifact() { return artifact; }
    public SemanticVectorRecall recaller() { return index == null ? SemanticVectorRecall.NONE : index; }
    public float[] encodeOnModelLane(String text, boolean query, BooleanSupplier cancelled) throws Exception {
        // Interactive recall keeps a 2 s total wait. Index backfill may await artifact I/O for
        // 30 s, but its installation survives an individual request's timeout/cancellation.
        long queryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        File config = preparation.await(query ? 2 : 30, TimeUnit.SECONDS,
                () -> closed.get() || cancelled.getAsBoolean());
        long inferenceNanos = query ? queryDeadline - System.nanoTime() : TimeUnit.SECONDS.toNanos(2);
        if (inferenceNanos <= 0) throw new TimeoutException("embedding query budget exhausted");
        AtomicBoolean abandoned = new AtomicBoolean();
        BooleanSupplier stopped = () -> closed.get() || abandoned.get() || cancelled.getAsBoolean();
        FutureTask<float[]> work = new FutureTask<>(() -> {
            if (stopped.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if (embedder == null) {
                if (System.nanoTime() < retryAfterNanos) throw new IllegalStateException("embedding retry backoff");
                try { embedder = new MnnOnDeviceEmbedder(config.getAbsolutePath(), artifact.profile()); }
                catch (RuntimeException unavailable) {
                    retryAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    throw unavailable;
                }
            }
            if (stopped.getAsBoolean()) {
                if (closed.get()) { embedder.close(); embedder = null; }
                throw new java.util.concurrent.CancellationException();
            }
            String bounded = text.length() <= 8000 ? text : text.substring(0, 8000);
            try { return embedder.encode(query ? "为这个句子生成表示以用于检索相关文章：" + bounded : bounded, stopped); }
            finally { if (closed.get() && embedder != null) { embedder.close(); embedder = null; } }
        });
        executors.modelExecutor().execute(work);
        try { return work.get(inferenceNanos, TimeUnit.NANOSECONDS); }
        finally { abandoned.set(true); work.cancel(false); }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        preparation.close();
        if (index != null) index.close();
        Runnable retire = () -> { if (embedder != null) { embedder.close(); embedder = null; } };
        // Retirement is ordered after in-flight inference; never destroy a native handle on a caller thread.
        try { executors.modelExecutor().execute(retire); }
        catch (java.util.concurrent.RejectedExecutionException busy) {
            try {
                executors.modelRetirementScheduler().schedule(() -> {
                    try { executors.modelExecutor().execute(retire); }
                    catch (java.util.concurrent.RejectedExecutionException ignored) { /* Process shutdown owns native reclamation. */ }
                }, 100, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException shutdown) {
                // The process is retiring both lanes; do not destroy an in-flight native handle here.
            }
        }
    }
}
