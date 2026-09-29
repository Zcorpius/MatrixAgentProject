package com.matrix.agent.platform.media;

import android.os.SystemClock;
import com.matrix.agent.identity.CancellationToken;
import java.util.Objects;

/** One immutable tool invocation, propagated through locks, handoff, fallback launch and readback. */
public record LaunchContext(String runtimeRequestId, String operationId,
        long deadlineElapsedMillis, CancellationToken cancellation) {
    public LaunchContext {
        Objects.requireNonNull(runtimeRequestId);
        Objects.requireNonNull(operationId);
        Objects.requireNonNull(cancellation);
    }
    public void checkActive() throws MediaPlatformException {
        if (cancellation.isCancelled()) throw new MediaPlatformException("EXECUTION_CANCELLED");
        if (SystemClock.elapsedRealtime() >= deadlineElapsedMillis) {
            throw new MediaPlatformException("OPERATION_DEADLINE_EXCEEDED");
        }
    }
}
