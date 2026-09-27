package com.matrix.agent.schedule.store;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.schedule.domain.ScheduleFailure;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** Rejects pre-reset queued commands even after the durable reset marker has been cleared. */
public final class ScheduleCommandFence {
    private final AtomicLong generation = new AtomicLong();

    public long capture() { return generation.get(); }
    public void invalidate() { generation.incrementAndGet(); }

    public <T> T execute(ScheduleStore store, long submittedGeneration, Function<ScheduleStore, T> command) {
        return store.database().runInTransaction(() -> {
            store.checkAvailable();
            if (submittedGeneration != generation.get()) {
                throw new ScheduleFailure(MatrixErrorCode.INVALID_STATE, "数据已清除，旧计划操作已失效");
            }
            return command.apply(store);
        });
    }
}
