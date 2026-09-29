package com.matrix.agent.evaluation;

import com.matrix.agent.contract.ApiProtocol;
import com.matrix.agent.contract.ModelConfig;
import com.matrix.agent.contract.PlannerMode;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

record ModelProfiles(int repetitions, List<Profile> profiles) {
    record Profile(String id, ModelConfig config) {}

    static ModelProfiles load(Path path) throws IOException, org.json.JSONException {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
        int repetitions = root.getInt("repetitions");
        if (repetitions < 1 || repetitions > 10) throw new IllegalArgumentException("repetitions must be 1..10");
        List<Profile> profiles = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> configurations = new HashSet<>();
        for (Object item : JsonValues.values(root.getJSONArray("profiles"))) {
            JSONObject value = (JSONObject) item;
            String id = value.getString("id");
            if (!id.matches("[a-z0-9][a-z0-9_]{0,39}") || !ids.add(id)) {
                throw new IllegalArgumentException("invalid/duplicate model profile id");
            }
            if (value.has("apiKey")) throw new IllegalArgumentException("use apiKeyEnv; do not store credentials in profiles");
            String keyVariable = value.optString("apiKeyEnv", "");
            String key = keyVariable.isEmpty() ? "" : System.getenv(keyVariable);
            if (key == null) throw new IllegalArgumentException("model credential environment variable is not set");
            ApiProtocol protocol = ApiProtocol.valueOf(value.getString("protocol"));
            if (protocol == ApiProtocol.ON_DEVICE) {
                throw new IllegalArgumentException("native model inference requires device validation; JVM supports HTTP endpoints");
            }
            ModelConfig config = new ModelConfig(id, id, protocol, value.getString("endpoint"),
                    value.getString("model"), key, !keyVariable.isEmpty(),
                    PlannerMode.valueOf(value.getString("plannerMode")));
            config.validate();
            if (!configurations.add(protocol.name() + "\n" + config.endpoint + "\n" + config.model + "\n" + config.plannerMode)) {
                throw new IllegalArgumentException("duplicate model configuration; use repetitions for resampling");
            }
            profiles.add(new Profile(id, config));
        }
        if (profiles.isEmpty() || profiles.size() > 8) throw new IllegalArgumentException("profiles must contain 1..8 configurations");
        return new ModelProfiles(repetitions, List.copyOf(profiles));
    }
}
