package com.matrix.agent.session;

import android.util.Log;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Race-safe, reference-counted lock registry keyed by session id. */
public final class SessionLockManager {
    private static final String TAG = "MatrixAgent";
    private final ConcurrentHashMap<String, LockEntry> entries = new ConcurrentHashMap<>();

    public Handle tryAcquire(String sessionId, long timeoutMillis) throws InterruptedException {
        Log.d(TAG, "[Lock] tryAcquire session=" + sessionId + " timeoutMs=" + timeoutMillis);
        LockEntry entry = entries.compute(sessionId, (key, current) -> {
            LockEntry value = current == null ? new LockEntry() : current;
            value.references++;
            return value;
        });
        boolean acquired = false;
        try {
            acquired = entry.lock.tryLock(timeoutMillis, TimeUnit.MILLISECONDS);
            if (acquired) {
                Log.d(TAG, "[Lock] acquired session=" + sessionId
                        + " entries=" + entries.size() + " refCount=" + entry.references);
                return new Handle(sessionId, entry);
            }
            Log.w(TAG, "[Lock] FAILED to acquire session=" + sessionId
                    + " within " + timeoutMillis + "ms (contended)");
            return null;
        } finally {
            if (!acquired) releaseReference(sessionId, entry);
        }
    }

    public int entryCount() {
        return entries.size();
    }

    private void releaseReference(String sessionId, LockEntry expected) {
        entries.computeIfPresent(sessionId, (key, current) -> {
            if (current != expected) return current;
            current.references--;
            return current.references == 0 ? null : current;
        });
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock(true);
        private int references;
    }

    public final class Handle implements AutoCloseable {
        private final String sessionId;
        private final LockEntry entry;
        private boolean closed;

        private Handle(String sessionId, LockEntry entry) {
            this.sessionId = sessionId;
            this.entry = entry;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            entry.lock.unlock();
            int queueLength = entry.lock.getQueueLength();
            releaseReference(sessionId, entry);
            Log.d(TAG, "[Lock] released session=" + sessionId
                    + " waitingThreads=" + queueLength);
        }
    }
}
