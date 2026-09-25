package com.matrix.agent.data.memory;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;

/** Request factory for instrumented writer boundary tests. */
public final class MemoryTestRequests {
    private MemoryTestRequests() { }

    public static AgentRequest from(Object userId, Object zone, Object sessionId, long epoch) {
        VehicleZone parsed = VehicleZone.parse(zone);
        if (userId == null || parsed == null) return null;
        Actor actor = "demo-passenger".equals(userId) ? Actor.PASSENGER : Actor.DRIVER;
        return AgentRequest.builder("记住测试事实", actor)
                .occupantZone(parsed)
                .sessionId(sessionId instanceof String ? (String) sessionId : "test-session")
                .epoch(epoch).build();
    }
}
