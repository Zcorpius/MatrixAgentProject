package com.matrix.agent.data.memory;

/** Mutation result; callers must not infer storage or capacity errors from a boolean. */
public enum MemoryWriteOutcome {
    SAVED,
    STALE_EPOCH,
    CAPACITY_REACHED,
    STORAGE_FAILURE,
    INVALID_KEY,
    INVALID_VALUE,
    INVALID_REQUEST
}
