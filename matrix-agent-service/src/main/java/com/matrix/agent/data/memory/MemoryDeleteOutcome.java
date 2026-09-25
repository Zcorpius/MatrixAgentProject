package com.matrix.agent.data.memory;

/** Distinguishes absence, stale requests and storage failure at the user control boundary. */
public enum MemoryDeleteOutcome {
    DELETED,
    NOT_FOUND,
    STALE_EPOCH,
    INVALID_KEY,
    INVALID_REQUEST,
    TARGET_NOT_AUTHORIZED,
    STORAGE_FAILURE
}
