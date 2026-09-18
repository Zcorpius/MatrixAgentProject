package com.matrix.agent.task.durable;
import com.matrix.agent.task.*;

import androidx.annotation.NonNull;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.data.db.MatrixDatabase;

/**
 * Single fail-closed admission gate for every Host mutation.
 *
 * <p>A missing SQLCipher database is not a recoverable in-memory mode for cross-process work:
 * accepting a command without an idempotency/recovery ledger would allow duplicate side effects
 * after process death.  The gate therefore rejects before any executor, network or voice action
 * is scheduled.  It is deliberately tiny so all Binder stubs can share the same policy.
 */
public final class PersistenceGate {
    private final MatrixDatabase database;

    public PersistenceGate(MatrixDatabase database) {
        this.database = database;
    }

    public boolean isAvailable() {
        return database != null && database.isOpen();
    }

    public int writeErrorCode() {
        return isAvailable() ? MatrixErrorCode.SUCCESS : MatrixErrorCode.PERSISTENCE_UNAVAILABLE;
    }

    public @NonNull String unavailableReason() {
        return "persistent storage unavailable";
    }
}
