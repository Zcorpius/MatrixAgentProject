package com.matrix.agent.host;

import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Semaphore;

/**
 * A single-worker scheduler with an explicit cap on registered delayed/periodic work.
 *
 * <p>{@link java.util.concurrent.ScheduledThreadPoolExecutor}'s delayed queue is deliberately
 * unbounded. That is unsuitable for Host retry/watchdog paths where a faulty caller could keep
 * scheduling faster than the worker can drain. This class fails fast instead of hiding memory
 * growth. It is intentionally package-private; the registry is the only owner.
 */
final class BoundedScheduledExecutor extends ScheduledThreadPoolExecutor {
    private final Semaphore permits;

    BoundedScheduledExecutor(String name, int maxPending) {
        super(1, daemonFactory(name), new AbortPolicy());
        if (maxPending <= 0) throw new IllegalArgumentException("maxPending must be positive");
        permits = new Semaphore(maxPending);
        setRemoveOnCancelPolicy(true);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        acquirePermit();
        ReleaseGate gate = new ReleaseGate(permits);
        try {
            return new PermitReleasingFuture(super.schedule(() -> {
                try {
                    command.run();
                } finally {
                    gate.release();
                }
            }, delay, unit), gate);
        } catch (RuntimeException error) {
            gate.release();
            throw error;
        }
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        acquirePermit();
        ReleaseGate gate = new ReleaseGate(permits);
        try {
            return new PermitReleasingFuture(super.schedule(() -> {
                try {
                    return callable.call();
                } finally {
                    gate.release();
                }
            }, delay, unit), gate);
        } catch (RuntimeException error) {
            gate.release();
            throw error;
        }
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
            long period, TimeUnit unit) {
        acquirePermit();
        ReleaseGate gate = new ReleaseGate(permits);
        try {
            // Preserve the interface method for callers while deliberately avoiding catch-up
            // bursts after a cached process resumes. Host periodic work is serial by design.
            return new PermitReleasingFuture(super.scheduleWithFixedDelay(command, initialDelay,
                    period, unit), gate);
        } catch (RuntimeException error) {
            gate.release();
            throw error;
        }
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
            long delay, TimeUnit unit) {
        acquirePermit();
        ReleaseGate gate = new ReleaseGate(permits);
        try {
            return new PermitReleasingFuture(super.scheduleWithFixedDelay(command, initialDelay,
                    delay, unit), gate);
        } catch (RuntimeException error) {
            gate.release();
            throw error;
        }
    }

    private void acquirePermit() {
        if (!permits.tryAcquire()) {
            throw new RejectedExecutionException("scheduled task capacity exhausted");
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class PermitReleasingFuture<V> implements ScheduledFuture<V> {
        private final ScheduledFuture<V> delegate;
        private final ReleaseGate gate;

        PermitReleasingFuture(ScheduledFuture<V> delegate, ReleaseGate gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override public long getDelay(TimeUnit unit) { return delegate.getDelay(unit); }
        @Override public int compareTo(java.util.concurrent.Delayed other) {
            return delegate.compareTo(other);
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = delegate.cancel(mayInterruptIfRunning);
            gate.release();
            return cancelled;
        }
        @Override public boolean isCancelled() { return delegate.isCancelled(); }
        @Override public boolean isDone() { return delegate.isDone(); }
        @Override public V get() throws java.util.concurrent.ExecutionException,
                InterruptedException { return delegate.get(); }
        @Override public V get(long timeout, TimeUnit unit)
                throws java.util.concurrent.ExecutionException, InterruptedException,
                java.util.concurrent.TimeoutException { return delegate.get(timeout, unit); }
    }

    private static final class ReleaseGate {
        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        ReleaseGate(Semaphore permits) {
            this.permits = permits;
        }

        void release() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
