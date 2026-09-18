package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.identity.VehicleZone;

import org.junit.Test;

/** Ensures the preference-recall adapter does not reintroduce user-only leakage. */
public final class LegacyPreferenceMemorySourceZoneIsolationTest {
    @Test
    public void recallsOnlyPreferencesFromRequestedZone() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryScope driver = new MemoryScope("same-account", VehicleZone.DRIVER);
        MemoryScope passenger = new MemoryScope("same-account", VehicleZone.PASSENGER);
        store.putPreference(driver, "seat", "driver-profile");
        store.putPreference(passenger, "seat", "passenger-profile");

        LegacyPreferenceMemorySource source = new LegacyPreferenceMemorySource(store);

        assertEquals(1, source.recallPreference(driver, "", 8).size());
        assertEquals("driver-profile", source.recallPreference(driver, "", 8).get(0).getValue());
        assertEquals("passenger-profile", source.recallPreference(passenger, "", 8)
                .get(0).getValue());
        assertTrue(source.recallPreference(MemoryScope.ofLegacy("same-account"), "", 8).isEmpty());
    }
}
