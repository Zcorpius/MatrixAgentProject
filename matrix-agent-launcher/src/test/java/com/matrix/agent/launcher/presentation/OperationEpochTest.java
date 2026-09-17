package com.matrix.agent.launcher.presentation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Guards the latest-intent-wins rule shared by task, model and download screens. */
public final class OperationEpochTest {
    @Test
    public void newerIntentInvalidatesEarlierCompletion() {
        OperationEpoch epoch = new OperationEpoch();

        long first = epoch.begin();
        assertTrue(epoch.isCurrent(first));
        long second = epoch.begin();

        assertFalse(epoch.isCurrent(first));
        assertTrue(epoch.isCurrent(second));
    }

    @Test
    public void readCanCaptureCurrentIntentWithoutInvalidatingIt() {
        OperationEpoch epoch = new OperationEpoch();

        long command = epoch.begin();
        long read = epoch.current();

        assertTrue(epoch.isCurrent(command));
        assertTrue(epoch.isCurrent(read));
    }
}
