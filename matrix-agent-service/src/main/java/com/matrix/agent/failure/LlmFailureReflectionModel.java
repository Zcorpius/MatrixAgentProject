package com.matrix.agent.failure;

import com.matrix.agent.contract.LlmClient;
import com.matrix.agent.contract.ModelConfig;
import com.matrix.agent.identity.CancellationToken;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Supplier;

/** Sends only the safe projection; strict output decoding rejects extra fields and invented evidence. */
public final class LlmFailureReflectionModel implements FailureReflectionService.Model {
    private final LlmClient client;
    private final Supplier<ModelConfig> config;
    public LlmFailureReflectionModel(LlmClient client, Supplier<ModelConfig> config) {
        this.client = client;
        this.config = config;
    }
    @Override public FailureLesson reflect(FailureEvidence evidence, CancellationToken token,
            long deadline) throws Exception {
        ModelConfig selected = config.get();
        if (selected == null || selected.protocol == com.matrix.agent.contract.ApiProtocol.ON_DEVICE) {
            return FailureLesson.unknown();
        }
        JSONObject input = new JSONObject().put("state", evidence.state().name())
                .put("stopReason", evidence.stopReason().name());
        JSONArray items = new JSONArray();
        for (var item : evidence.items()) items.put(new JSONObject().put("ref", item.ref())
                .put("capability", item.capability()).put("signal", item.signal().name())
                .put("verified", item.verified()));
        input.put("evidence", items);
        String prompt = "Classify diagnostic evidence only. Do not infer entity ambiguity, user intent, "
                + "permissions or root causes absent from evidence. Return exactly a JSON object with "
                + "version=1, category, lessonCode, evidenceRefs (0-3 integer refs). Codes/categories: "
                + "CHECK_PARAMETERS/PARAMETERS for PARAMETER_REJECTED; RESPECT_CAPABILITY_DENIAL/POLICY "
                + "for CAPABILITY_REJECTED; VERIFY_RESULT/VERIFICATION for VERIFICATION_FAILED; "
                + "RECONCILE_UNKNOWN/VERIFICATION for EXECUTION_UNKNOWN. Otherwise UNKNOWN/UNKNOWN with [].";
        return decode(client.complete(selected, prompt, input.toString(), token, deadline), evidence);
    }

    public static FailureLesson decode(String raw, FailureEvidence evidence) throws Exception {
        if (raw == null || raw.length() > 1024) throw new IllegalArgumentException("reflection size");
        JSONObject json = new JSONObject(raw);
        Set<String> keys = Set.of("version", "category", "lessonCode", "evidenceRefs");
        if (json.length() != keys.size()) throw new IllegalArgumentException("reflection fields");
        var names = json.keys();
        while (names.hasNext()) if (!keys.contains(names.next())) throw new IllegalArgumentException("reflection field");
        if (!(json.get("version") instanceof Integer) || json.getInt("version") != 1) {
            throw new IllegalArgumentException("reflection version");
        }
        var code = FailureLesson.Code.valueOf(json.getString("lessonCode"));
        if (!code.category().equals(json.getString("category"))) throw new IllegalArgumentException("category");
        JSONArray refs = json.getJSONArray("evidenceRefs");
        if (refs.length() > 3) throw new IllegalArgumentException("evidence count");
        var references = new ArrayList<Integer>();
        for (int i = 0; i < refs.length(); i++) {
            if (!(refs.get(i) instanceof Integer)) throw new IllegalArgumentException("reference type");
            references.add(refs.getInt(i));
        }
        FailureLesson lesson = new FailureLesson(1, code, references);
        return lesson.groundedIn(evidence) ? lesson : FailureLesson.unknown();
    }
}
