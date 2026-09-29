package com.matrix.agent.embedding;

import java.io.File;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** One shared, cancellable-on-close artifact installation; request timeouts never discard its progress. */
public final class EmbeddingPreparation implements AutoCloseable {
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final EmbeddingArtifact artifact;
    private final EmbeddingArtifact.Source source;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private FutureTask<File> installation;
    private volatile long retryAfterNanos;

    public EmbeddingPreparation(EmbeddingArtifact artifact, EmbeddingArtifact.Source source,
            ExecutorService executor) {
        this.artifact = Objects.requireNonNull(artifact);
        this.source = Objects.requireNonNull(source);
        this.executor = Objects.requireNonNull(executor);
    }

    public File await(long timeout, TimeUnit unit, BooleanSupplier requestCancelled) throws Exception {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(requestCancelled);
        if (timeout <= 0) throw new TimeoutException("embedding preparation budget exhausted");
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        FutureTask<File> task = installation();
        while (true) {
            if (closed.get() || requestCancelled.getAsBoolean()) throw new CancellationException();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("embedding preparation budget exhausted");
            try {
                File config = task.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200)), TimeUnit.NANOSECONDS);
                if (closed.get() || requestCancelled.getAsBoolean()) throw new CancellationException();
                return config;
            }
            catch (TimeoutException pending) { /* Caller may stop waiting; installation continues. */ }
        }
    }

    private synchronized FutureTask<File> installation() {
        if (closed.get()) throw new CancellationException();
        if (installation == null || (installation.isDone() && retryAfterNanos != 0
                && System.nanoTime() >= retryAfterNanos)) {
            if (installation != null && !installation.isDone()) throw new AssertionError("installation state");
            FutureTask<File> next = new FutureTask<>(() -> {
                try {
                    File config;
                    try { config = artifact.verifiedConfig(); }
                    catch (IOException absent) { config = artifact.install(source, closed::get); }
                    retryAfterNanos = 0;
                    return config;
                } catch (Exception unavailable) {
                    retryAfterNanos = System.nanoTime() + RETRY_NANOS;
                    throw unavailable;
                }
            });
            executor.execute(next);
            installation = next;
        }
        return installation;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        synchronized (this) {
            if (installation != null) installation.cancel(true);
        }
    }
}
