package com.matrix.agent.ondevice;

import org.junit.Test;

import static org.junit.Assert.fail;

/** Unit-level guard for values that are later serialized into the JNI configuration. */
public class MnnLoadOptionsTest {

    @Test
    public void cpuDefaultsAreAccepted() {
        MnnLoadOptions.cpuDefaults().validate();
    }

    @Test
    public void unsupportedBackendAndMalformedLevelsAreRejected() {
        assertRejected(new MnnLoadOptions("vulkan", 4, "low", "low", null));
        assertRejected(new MnnLoadOptions("cpu", 4, "low\"}", "low", null));
        assertRejected(new MnnLoadOptions("cpu", 0, "low", "low", null));
    }

    private static void assertRejected(MnnLoadOptions options) {
        try {
            options.validate();
            fail("invalid native configuration must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
