package com.matrix.agent.model;

import org.junit.Test;

import static org.junit.Assert.fail;

/** Ensures an on-device model id can never escape Host-private model storage. */
public class ModelConfigOnDeviceValidationTest {

    @Test
    public void acceptsSingleSafeDirectoryName() {
        new ModelConfig("on_device", "Qwen", ApiProtocol.ON_DEVICE, "",
                "Qwen3-0.6B-MNN", "", false).validate();
    }

    @Test
    public void rejectsTraversalAndNestedNames() {
        assertRejected("../other");
        assertRejected("nested/model");
        assertRejected(".hidden");
        assertRejected("");
    }

    @Test
    public void remoteConfigRejectsUnsafePersistedEndpointAndOversizedSecret() {
        assertRemoteRejected("https://api.example.com/v1#fragment", "key");
        assertRemoteRejected("https://api.example.com/v1", "x".repeat(8 * 1024 + 1));
    }

    private static void assertRejected(String model) {
        try {
            new ModelConfig("on_device", "test", ApiProtocol.ON_DEVICE, "", model, "", false)
                    .validate();
            fail("unsafe model id must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void assertRemoteRejected(String endpoint, String secret) {
        try {
            new ModelConfig("remote", "Remote", ApiProtocol.OPENAI_CHAT, endpoint,
                    "model-1", secret, false).validate();
            fail("unsafe remote config must be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
