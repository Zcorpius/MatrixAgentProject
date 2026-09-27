package com.matrix.agent.host.di;

import android.content.Context;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.store.ScheduleStore;
import com.matrix.agent.schedule.tool.ScheduleCapabilityProvider;
import java.util.function.Function;

/** Converts a captured interactive origin to the same owner policy as the Binder facade. */
final class ScheduleToolBackend implements ScheduleCapabilityProvider.Backend {
    private final Context context;
    private final ScheduleGraph schedules;
    ScheduleToolBackend(Context context, ScheduleGraph schedules) { this.context = context; this.schedules = schedules; }
    @Override public ClockSample clock() { return schedules.clock().sample(); }
    @Override public <T> T execute(Function<ScheduleStore, T> action) { return schedules.call(action); }
    @Override public void changed() { schedules.changed(); }
    @Override public ScheduleIdentity identity(AgentRequest request) {
        var origin = request.getInteractiveOrigin();
        if (origin == null || origin.androidUserId() != 0) throw new SecurityException("unsupported plan owner");
        var identity = new ScheduleIdentity(origin.uid(), 0, origin.packageName(),
                ScheduleCallerIdentity.signature(context, origin.packageName()), request.getActor(), request.getOccupantZone());
        if (!schedules.authorized(identity)) throw new SecurityException("plan owner revoked");
        return identity;
    }
}
