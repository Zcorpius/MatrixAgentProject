package com.matrix.agent.host;

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
final class PersistenceGate {
    private final MatrixDatabase database;

    PersistenceGate(MatrixDatabase database) {
        this.database = database;
    }

    boolean isAvailable() {
        return database != null && database.isOpen();
    }

    int writeErrorCode() {
        return isAvailable() ? MatrixErrorCode.SUCCESS : MatrixErrorCode.PERSISTENCE_UNAVAILABLE;
    }

    @NonNull String unavailableReason() {
        return "persistent storage unavailable";
    }
}
