package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.session.SessionManager;

import org.junit.Test;

public final class SessionContextWorkingMemoryScopeTest {
    @Test public void onlyBoundOwnerCanReadSessionTurns() {
        SessionManager manager = new SessionManager();
        manager.bindOrCreate("session", "demo-driver", VehicleZone.DRIVER)
                .rememberTemperature(24, "driver");
        SessionContextWorkingMemory source = new SessionContextWorkingMemory(manager);

        assertEquals(1, source.recallWorking(new MemoryScope("demo-driver", VehicleZone.DRIVER),
                "session", "刚才空调设多少度", 4).size());
        assertTrue(source.recallWorking(new MemoryScope("demo-passenger", VehicleZone.PASSENGER),
                "session", "刚才空调设多少度", 4).isEmpty());
        assertTrue(source.recallWorking(new MemoryScope("demo-driver", VehicleZone.PASSENGER),
                "session", "刚才空调设多少度", 4).isEmpty());
    }

    @Test public void unboundLegacyTurnsAreNotAdopted() {
        SessionManager manager = new SessionManager();
        manager.getOrCreate("session").addTurn("old");
        assertTrue(manager.bindOrCreate("session", "demo-driver", VehicleZone.DRIVER)
                .getRecentTurns().isEmpty());
    }
}
