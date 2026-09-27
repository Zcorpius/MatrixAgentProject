package com.matrix.agent.launcher.overlay.pet;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PetGreetingPolicyTest {
    @Test public void greetsFirstAppearanceButNotRepeatedRendersOrBriefAbsence() {
        var policy = new PetGreetingPolicy();
        assertFalse(policy.visibility(null, 0));
        assertTrue(policy.visibility("one", 100));
        assertFalse(policy.visibility("one", 1_000_000)); // Visible duration is not away time.
        assertFalse(policy.visibility(null, 1_000_001));
        assertFalse(policy.visibility("one", 1_000_100));
    }
    @Test public void returnsAfterFiveMinutesAreMeasuredFromLastDeparture() {
        var policy = new PetGreetingPolicy();
        assertTrue(policy.visibility("one", 0));
        policy.visibility(null, 100);
        assertFalse(policy.visibility("one", 300_099));
        policy.visibility(null, 400_000);
        assertTrue(policy.visibility("one", 700_000));
        assertFalse(policy.visibility("one", 700_001));
    }
    @Test public void conversationsHaveIndependentGreetingHistory() {
        var policy = new PetGreetingPolicy();
        assertTrue(policy.visibility("one", 0));
        assertTrue(policy.visibility("two", 100));
        assertFalse(policy.visibility("one", 200));
        assertFalse(policy.visibility("two", 300));
    }
}
