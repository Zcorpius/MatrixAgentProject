package com.matrix.agent.schedule.store;

/** Bounded terminal history cleanup; uncertain effects and pending deliveries remain reviewable. */
public final class ScheduleRetention {
    private ScheduleRetention() { }
    public static void prune(ScheduleStore store, long now) {
        store.database().runInTransaction(() -> {
            store.checkAvailable();
            for (String id : store.dao().expiredRuns(now - 30L * 86_400_000)) {
                store.dao().deleteRunAcceptances(id); store.dao().deleteRunSteps(id); store.dao().deleteRun(id);
            }
            store.dao().pruneEvents(); store.dao().pruneBindings(now - 30L * 86_400_000);
        });
    }
}
