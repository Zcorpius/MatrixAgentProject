package com.matrix.agent.evaluation;

import static org.junit.Assert.assertThrows;

import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class DeviceEvaluationRunnerResumeTest {
    @Test public void prefixMustMatchTheFrozenCorpusAcrossRepetitions() throws Exception {
        List<EvaluationCase> corpus = List.of(
                new EvaluationCase("media-a", EvaluationCase.Suite.MEDIA, List.of(), Map.of(), List.of(), "goal-effects-v1"),
                new EvaluationCase("schedule-b", EvaluationCase.Suite.SCHEDULE, List.of(), Map.of(), List.of(), "goal-effects-v1"));
        JSONArray cases = new JSONArray()
                .put(row("media-a", "MEDIA", 1)).put(row("schedule-b", "SCHEDULE", 1))
                .put(row("media-a", "MEDIA", 2));
        DeviceEvaluationRunner.validateCasePrefix(cases, corpus);
        cases.getJSONObject(2).put("suite", "SCHEDULE");
        assertThrows(IllegalArgumentException.class,
                () -> DeviceEvaluationRunner.validateCasePrefix(cases, corpus));
    }

    private static JSONObject row(String id, String suite, int repeat) throws Exception {
        return new JSONObject().put("id", id).put("suite", suite).put("repeat", repeat);
    }
}
