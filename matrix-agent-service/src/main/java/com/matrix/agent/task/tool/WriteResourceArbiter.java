package com.matrix.agent.task.tool;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded stripes serialize writes to the same capability domain across all execution origins. */
final class WriteResourceArbiter {
    private final ReentrantLock[] stripes = new ReentrantLock[32];

    WriteResourceArbiter() {
        for (int i = 0; i < stripes.length; i++) stripes[i] = new ReentrantLock(true);
    }

    AutoCloseable acquire(String capability, long timeoutMillis) throws InterruptedException {
        int separator = capability.indexOf('.');
        String domain = separator < 0 ? capability : capability.substring(0, separator);
        ReentrantLock lock = stripes[Math.floorMod(domain.hashCode(), stripes.length)];
        return lock.tryLock(Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS) ? lock::unlock : null;
    }
}
