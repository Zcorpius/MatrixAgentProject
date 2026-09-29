package com.matrix.agent.evaluation;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModelProfilesTest {
    @Test public void localEndpointConfigNeedsNoCredentialAndNeverStartsInference() throws Exception {
        var loaded = load(new JSONObject().put("repetitions", 2).put("profiles", new JSONArray().put(profile("local"))));
        assertEquals(2, loaded.repetitions());
        assertEquals("fixture-model", loaded.profiles().get(0).config().model);
    }

    @Test public void renamingOneConfigurationCannotSatisfyTwoConfigurationSampling() throws Exception {
        JSONObject root = new JSONObject().put("repetitions", 2).put("profiles",
                new JSONArray().put(profile("first")).put(profile("second")));
        assertThrows(IllegalArgumentException.class, () -> load(root));
    }

    @Test public void inlineCredentialsAreRejectedWithoutEchoingThem() throws Exception {
        JSONObject root = new JSONObject().put("repetitions", 2).put("profiles",
                new JSONArray().put(profile("local").put("apiKey", "synthetic-sensitive-sentinel")));
        var error = assertThrows(IllegalArgumentException.class, () -> load(root));
        assertFalse(error.toString().contains("synthetic-sensitive-sentinel"));
    }

    private static JSONObject profile(String id) throws Exception {
        return new JSONObject().put("id", id).put("protocol", "OLLAMA_CHAT")
                .put("endpoint", "http://127.0.0.1:11434/api/chat").put("model", "fixture-model")
                .put("plannerMode", "STRUCTURED_JSON_COMPATIBILITY");
    }
    private static ModelProfiles load(JSONObject value) throws Exception {
        Path path = Files.createTempFile("matrix-model-profiles", ".json");
        try {
            Files.write(path, value.toString().getBytes(StandardCharsets.UTF_8));
            return ModelProfiles.load(path);
        } finally { Files.deleteIfExists(path); }
    }
}
