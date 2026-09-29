package com.matrix.agent.evaluation;

import com.matrix.agent.model.LlmModelGateway;
import com.matrix.agent.model.ModelApiClient;
import okhttp3.OkHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/** Explicit Gradle entrypoints; ordinary unit tests never contact a real model. */
public final class EvaluationRunnerTest {
    @Test public void deterministicCorpus() throws Exception {
        Assume.assumeTrue("offline".equals(System.getProperty("evaluation.mode")));
        execute(false);
    }

    @Test public void realModelCorpus() throws Exception {
        Assume.assumeTrue("model".equals(System.getProperty("evaluation.mode")));
        execute(true);
    }

    private void execute(boolean online) throws Exception {
        Path project = Path.of(System.getProperty("evaluation.projectDir"));
        Path corpusPath = project.resolve("src/test/resources/evaluation/corpus-v1.json");
        List<EvaluationCase> corpus = Corpus.load(corpusPath);
        Path output = Path.of(System.getProperty("evaluation.outputDir"));
        Files.createDirectories(output);
        JSONObject report = Provenance.collect(project, corpusPath, online ? "REAL_MODEL_FIXED_FIXTURES" : "SCRIPTED_PRODUCTION_PROVIDERS");
        report.put("startedAt", Instant.now().toString()).put("complete", false);
        JSONArray runs = new JSONArray();
        report.put("runs", runs);
        save(output, report);
        ModelProfiles profiles = online ? ModelProfiles.load(Path.of(System.getProperty("evaluation.modelConfig")))
                : new ModelProfiles(2, List.of(new ModelProfiles.Profile("scripted", null)));
        report.put("repetitions", profiles.repetitions())
                .put("baselineSamplingRequirementMet", online && profiles.profiles().size() >= 2 && profiles.repetitions() >= 2);
        List<String> failures = new ArrayList<>();
        boolean unsafe = false;
        for (ModelProfiles.Profile profile : profiles.profiles()) {
            HttpMeasurements measurements = new HttpMeasurements();
            // No HTTP client or LlmModelGateway is constructed in deterministic mode.
            OkHttpClient client = online ? new OkHttpClient.Builder().addInterceptor(measurements)
                    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS).build() : null;
            JSONObject run = new JSONObject().put("configurationId", profile.id())
                    .put("model", online ? profile.config().model : "scripted")
                    .put("protocol", online ? profile.config().protocol.name() : "none")
                    .put("plannerMode", online ? profile.config().plannerMode.name() : "scripted")
                    .put("endpointSha256", online ? Provenance.sha256(profile.config().endpoint) : JSONObject.NULL);
            JSONArray cases = new JSONArray();
            run.put("cases", cases);
            runs.put(run);
            var gateway = online ? new LlmModelGateway(new ModelApiClient(client), profile.config()) : null;
            try (EvaluationHarness harness = new EvaluationHarness(project.resolve("src/main/assets"))) {
                for (int repeat = 0; repeat < profiles.repetitions(); repeat++) {
                    for (EvaluationCase scenario : corpus) {
                        List<EvaluationHarness.Trace> traces = harness.run(scenario, gateway);
                        JSONArray turns = new JSONArray();
                        boolean passed = true;
                        for (int i = 0; i < traces.size(); i++) {
                            var trace = traces.get(i);
                            var expectation = scenario.turns().get(i).expect();
                            var score = new GoalScorer().score(expectation, trace);
                            passed &= score.passed();
                            unsafe |= score.unsafeExecution();
                            turns.put(new JSONObject().put("turn", i + 1).put("goal", expectation.goal())
                                    .put("passed", score.passed()).put("unsafeExecution", score.unsafeExecution())
                                    .put("primaryFailure", score.primary() == null ? JSONObject.NULL : score.primary().name())
                                    .put("secondaryFailures", new JSONArray(score.secondary()))
                                    .put("evidence", new JSONArray(score.evidence()))
                                    .put("taskState", trace.outcome().getFinalState())
                                    .put("stopReason", trace.outcome().getStopReason())
                                    .put("durationMillis", trace.outcome().getDurationMillis())
                                    .put("modelCalls", trace.modelCalls())
                                    .put("providerCalls", trace.observations().size())
                                    .put("modelProposals", summarize(trace.modelProposals()))
                                    .put("engineProposals", summarize(trace.engineProposals()))
                                    .put("actualEffects", summarize(trace.effects()))
                                    .put("providerObservations", summarize(trace.observations()))
                                    .put("policyRejections", summarize(trace.policyRejections()))
                                    .put("systemPromptHashes", new JSONArray(trace.systemPromptHashes()))
                                    .put("answerSha256", Provenance.sha256(java.util.Objects.toString(trace.outcome().getFinalAssistantText(), ""))));
                            if (!score.passed()) failures.add(profile.id() + "/" + scenario.id() + "/turn-" + (i + 1)
                                    + ": " + score.primary() + " " + score.evidence());
                        }
                        cases.put(new JSONObject().put("id", scenario.id()).put("suite", scenario.suite())
                                .put("tags", new JSONArray(scenario.tags())).put("repeat", repeat + 1)
                                .put("passed", passed).put("turns", turns));
                        run.put("summary", summary(cases)).put("httpRequests", measurements.snapshot());
                        save(output, report); // Keep partial evidence even if an online run is interrupted.
                        if (online) System.out.println("eval " + profile.id() + " repeat=" + (repeat + 1)
                                + " case=" + scenario.id() + " passed=" + passed);
                    }
                }
            } finally {
                if (client != null) {
                    client.dispatcher().cancelAll();
                    client.dispatcher().executorService().shutdownNow();
                    client.connectionPool().evictAll();
                }
            }
        }
        report.put("complete", true).put("finishedAt", Instant.now().toString())
                .put("comparison", comparison(runs));
        save(output, report);
        // Online goal scores are measurements, not a made-up passing threshold. Actual unsafe execution always gates.
        assertTrue("Unsafe execution in evaluation; inspect report.json", !unsafe);
        if (!online) assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private static JSONArray summarize(List<EvaluationCase.Action> actions) throws org.json.JSONException {
        JSONArray array = new JSONArray();
        for (var action : actions) {
            JSONObject row = new JSONObject().put("kind", action.kind())
                    .put("attributesSha256", Provenance.sha256(new JSONObject(action.attributes()).toString()));
            if (action.attributes().containsKey("status")) row.put("status", action.attributes().get("status"));
            if (action.attributes().containsKey("verified")) row.put("verified", action.attributes().get("verified"));
            array.put(row);
        }
        return array;
    }

    private static JSONObject summary(JSONArray cases) throws org.json.JSONException {
        int passed = 0, turns = 0, turnPassed = 0, clarification = 0, clarificationPassed = 0, unsafe = 0;
        long calls = 0, latency = 0;
        JSONObject failures = new JSONObject();
        for (Object item : JsonValues.values(cases)) {
            JSONObject scenario = (JSONObject) item;
            if (scenario.getBoolean("passed")) passed++;
            for (Object value : JsonValues.values(scenario.getJSONArray("turns"))) {
                JSONObject turn = (JSONObject) value;
                turns++;
                if (turn.getBoolean("passed")) turnPassed++;
                if (List.of("CLARIFICATION", "REJECTION").contains(turn.get("goal").toString())) {
                    clarification++;
                    if (turn.getBoolean("passed")) clarificationPassed++;
                }
                if (turn.getBoolean("unsafeExecution")) unsafe++;
                calls += turn.getLong("modelCalls");
                latency += turn.getLong("durationMillis");
                if (!turn.isNull("primaryFailure")) {
                    String failure = turn.getString("primaryFailure");
                    failures.put(failure, failures.optInt(failure) + 1);
                }
            }
        }
        return new JSONObject().put("caseSamples", cases.length()).put("casesPassed", passed)
                .put("caseGoalRate", ratio(passed, cases.length())).put("turnSamples", turns)
                .put("turnsPassed", turnPassed).put("turnGoalRate", ratio(turnPassed, turns))
                .put("clarificationOrRejectionSamples", clarification).put("clarificationOrRejectionPassed", clarificationPassed)
                .put("clarificationOrRejectionRate", ratio(clarificationPassed, clarification))
                .put("unsafeExecutedTurns", unsafe).put("unsafeExecutionRate", ratio(unsafe, turns))
                .put("modelCalls", calls).put("totalDurationMillis", latency).put("primaryFailures", failures);
    }

    private static Object ratio(int numerator, int denominator) {
        return denominator == 0 ? JSONObject.NULL : (double) numerator / denominator;
    }

    private static JSONArray comparison(JSONArray runs) throws org.json.JSONException {
        Map<String, JSONObject> rows = new java.util.TreeMap<>();
        for (Object value : JsonValues.values(runs)) {
            JSONObject run = (JSONObject) value;
            for (Object item : JsonValues.values(run.getJSONArray("cases"))) {
                JSONObject scenario = (JSONObject) item;
                String id = scenario.getString("id");
                JSONObject row = rows.get(id);
                if (row == null) {
                    row = new JSONObject().put("id", id).put("configurations", new JSONObject());
                    rows.put(id, row);
                }
                JSONObject configurations = row.getJSONObject("configurations");
                String profile = run.getString("configurationId");
                JSONArray samples = configurations.optJSONArray(profile);
                if (samples == null) { samples = new JSONArray(); configurations.put(profile, samples); }
                samples.put(scenario.getBoolean("passed"));
            }
        }
        for (JSONObject row : rows.values()) {
            JSONObject configurations = row.getJSONObject("configurations");
            JSONObject rates = new JSONObject();
            boolean stable = true;
            java.util.Set<Double> distinctRates = new java.util.HashSet<>();
            for (String profile : JsonValues.keys(configurations)) {
                List<Object> samples = JsonValues.values(configurations.getJSONArray(profile));
                stable &= samples.stream().distinct().count() == 1;
                double rate = (double) samples.stream().filter(Boolean.TRUE::equals).count() / samples.size();
                rates.put(profile, rate);
                distinctRates.add(rate);
            }
            row.put("passRates", rates).put("stableAcrossRepeats", stable)
                    .put("differentAcrossConfigurations", distinctRates.size() > 1);
        }
        return new JSONArray(rows.values());
    }

    private static void save(Path directory, JSONObject report) throws Exception {
        Path temporary = directory.resolve("report.json.tmp");
        Files.write(temporary, (report.toString(2) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.move(temporary, directory.resolve("report.json"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
