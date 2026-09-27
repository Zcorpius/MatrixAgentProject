package com.matrix.agent.schedule.domain;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.VehicleZone;

/** Host-derived identity captured at creation and revalidated before each automatic effect. */
public record ScheduleIdentity(int uid, int androidUserId, String packageName,
        String signatureDigest, Actor actor, VehicleZone zone) {
    public ScheduleIdentity {
        if (uid < 0 || androidUserId != 0 || uid / 100_000 != androidUserId || packageName == null || packageName.isBlank()
                || signatureDigest == null || signatureDigest.isBlank() || actor == null || zone == null) {
            throw new IllegalArgumentException("unsupported scheduling identity");
        }
        if ((actor == Actor.DRIVER && zone != VehicleZone.DRIVER)
                || (actor == Actor.PASSENGER && zone != VehicleZone.PASSENGER)) {
            throw new IllegalArgumentException("actor and zone do not match");
        }
    }
}
