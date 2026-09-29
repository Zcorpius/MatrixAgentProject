package com.matrix.agent.evaluation;

import android.content.Context;
import android.util.Log;
import com.matrix.agent.contract.ModelConfig;
import com.matrix.agent.contract.PlannerMode;
import com.matrix.agent.host.MatrixAgentApplication;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.model.LlmModelGateway;
import com.matrix.agent.model.ModelApiClient;
import com.matrix.agent.task.AgentBudget;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real configured model, production Engine/providers, isolated effects. Credentials stay in Host memory. */
public final class DeviceEvaluationRunner {
    private DeviceEvaluationRunner() { }
    public static void run(Context context, CancellationToken token) { run(context, token, false); }

    public static void run(Context context, CancellationToken token, boolean resume) {
        try {
            var container = ((MatrixAgentApplication) context).getContainer();
            ModelConfig saved = container.getModelConfigStore().load();
            if (saved == null || saved.protocol != com.matrix.agent.contract.ApiProtocol.OPENAI_CHAT) {
                throw new IllegalStateException("two-mode baseline needs an OpenAI-compatible saved configuration");
            }
            Path work = context.getCacheDir().toPath().resolve("evaluation-inputs");
            copyAssets(context, "skills", work);
            copyAssets(context, "evaluation/corpus-v1.json", work);
            Path corpusFile = work.resolve("evaluation/corpus-v1.json");
            List<EvaluationCase> corpus = Corpus.load(corpusFile);
            Path output = context.getExternalFilesDir(null).toPath().resolve("verification");
            Files.createDirectories(output);
            Path reportFile = output.resolve("real-model-report.json");
            JSONObject build;
            try (InputStream input = context.getAssets().open("evaluation/build-provenance.json")) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (bytes.size() + read > 4096) throw new java.io.IOException("evaluation provenance too large");
                    bytes.write(buffer, 0, read);
                }
                build = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            }
            AgentBudget budget = new AgentBudget();
            JSONObject fresh = new JSONObject().put("schemaVersion", 1).put("complete", false)
                    .put("mode", "REAL_MODEL_FIXED_FIXTURES_ON_DEVICE")
                    .put("startedAt", java.time.Instant.now().toString())
                    .put("model", saved.model).put("protocol", saved.protocol.name())
                    .put("build", build)
                    .put("budget", new JSONObject().put("maxIterations", budget.getMaxIterations())
                            .put("maxToolCalls", budget.getMaxToolCalls())
                            .put("deadlineMillis", budget.getTotalDeadlineMillis())
                            .put("maxMessageChars", budget.getMaxMessageChars())
                            .put("totalInputChars", budget.getTotalInputChars())
                            .put("maxMessageCount", budget.getMaxMessageCount()))
                    .put("corpusSha256", Provenance.sha256(Files.readAllBytes(corpusFile)))
                    .put("skillAssetsSha256", Provenance.treeHash(work.resolve("skills")))
                    .put("scoringVersion", Corpus.SCORING_VERSION).put("attributionVersion", GoalScorer.ATTRIBUTION_VERSION).put("fixtureVersion", Corpus.FIXTURE_VERSION)
                    .put("repetitions", 2).put("baselineSamplingRequirementMet", false)
                    .put("cost", JSONObject.NULL).put("runs", new JSONArray());
            JSONObject report = resume ? new JSONObject(new String(Files.readAllBytes(reportFile), StandardCharsets.UTF_8)) : fresh;
            if (resume) validateResume(report, fresh);
            JSONArray runs = report.getJSONArray("runs");
            JSONArray sessions = report.optJSONArray("evaluationSessions");
            if (sessions == null) { sessions = new JSONArray(); report.put("evaluationSessions", sessions); }
            sessions.put(new JSONObject().put("startedAt", java.time.Instant.now().toString())
                    .put("resumed", resume).put("deviceEvaluatorSourceSha256", build.getString("deviceEvaluatorSourceSha256"))
                    .put("evaluatorSourceSha256", build.getString("evaluatorSourceSha256"))
                    .put("startingCaseCounts", caseCounts(runs)));
            report.put("incompleteReason", JSONObject.NULL);
            save(output, report);
            List<PlannerMode> modes = List.of(PlannerMode.STRUCTURED_JSON_COMPATIBILITY, PlannerMode.NATIVE_TOOL_CALLING);
            for (int modeIndex = 0; modeIndex < modes.size(); modeIndex++) {
                PlannerMode mode = modes.get(modeIndex);
                ModelConfig config = new ModelConfig(saved.providerId, saved.displayName, saved.protocol,
                        saved.endpoint, saved.model, saved.apiKey, saved.apiKeyRequired, mode);
                JSONObject run;
                if (modeIndex < runs.length()) {
                    run = runs.getJSONObject(modeIndex);
                    if (!mode.name().equals(run.getString("configurationId"))
                            || !Provenance.sha256(config.endpoint).equals(run.getString("endpointSha256"))) {
                        throw new IllegalArgumentException("resume configuration mismatch");
                    }
                } else {
                    run = new JSONObject().put("configurationId", mode.name())
                            .put("endpointSha256", Provenance.sha256(config.endpoint))
                            .put("requestMeasurements", new JSONArray()).put("cases", new JSONArray());
                    runs.put(run);
                }
                JSONArray cases = run.getJSONArray("cases");
                validateCasePrefix(cases, corpus);
                HttpMeasurements measurements = new HttpMeasurements(run.getJSONArray("requestMeasurements"));
                var client = container.getHttpClient().provider().newBuilder()
                        .addInterceptor(measurements).build();
                var model = new LlmModelGateway(new ModelApiClient(client), config);
                try (var harness = new EvaluationHarness(work)) {
                    for (int caseIndex = cases.length(); caseIndex < 2 * corpus.size(); caseIndex++) {
                        int repeat = caseIndex / corpus.size() + 1;
                        var scenario = corpus.get(caseIndex % corpus.size());
                        if (token.isCancelled()) throw new InterruptedException("evaluation cancelled");
                        List<EvaluationHarness.Trace> traces;
                        int retry = 0;
                        while (true) {
                            int firstRequest = measurements.count();
                            traces = harness.run(scenario, model);
                            if (!measurements.hasStatusSince(firstRequest, 429)) break;
                            run.put("requestMeasurements", measurements.snapshot());
                            JSONArray backoffs = run.optJSONArray("providerBackoffEvents");
                            if (backoffs == null) { backoffs = new JSONArray(); run.put("providerBackoffEvents", backoffs); }
                            long pause = Math.min(15 * 60_000L, 60_000L << Math.min(retry, 4));
                            backoffs.put(new JSONObject().put("caseId", scenario.id()).put("repeat", repeat)
                                    .put("attempt", retry + 1).put("waitMillis", pause));
                            report.put("incompleteReason", "PROVIDER_RATE_LIMITED");
                            save(output, report);
                            if (++retry > 8) {
                                Log.w("MatrixEvaluation", "provider rate limit persisted; resume later");
                                return;
                            }
                            waitForQuota(token, pause);
                        }
                        JSONArray turns = new JSONArray();
                        boolean passed = true;
                        for (int i = 0; i < traces.size(); i++) {
                            var trace = traces.get(i);
                            var score = new GoalScorer().score(scenario.turns().get(i).expect(), trace);
                            passed &= score.passed();
                            turns.put(new JSONObject().put("turn", i + 1).put("passed", score.passed())
                                    .put("unsafeExecution", score.unsafeExecution())
                                    .put("primaryFailure", score.primary() == null ? JSONObject.NULL : score.primary().name())
                                    .put("secondaryFailures", new JSONArray(score.secondary()))
                                    .put("evidence", new JSONArray(score.evidence()))
                                    .put("taskState", trace.outcome().getFinalState().name())
                                    .put("stopReason", trace.outcome().getStopReason().name())
                                    .put("durationMillis", trace.outcome().getDurationMillis())
                                    .put("modelCalls", trace.modelCalls())
                                    .put("effects", summarize(trace.effects()))
                                    .put("modelProposals", summarize(trace.modelProposals()))
                                    .put("policyRejections", summarize(trace.policyRejections()))
                                    .put("systemPromptHashes", new JSONArray(trace.systemPromptHashes()))
                                    .put("answerSha256", Provenance.sha256(Objects.toString(trace.outcome().getFinalAssistantText(), ""))));
                        }
                        cases.put(new JSONObject().put("id", scenario.id()).put("suite", scenario.suite())
                                .put("repeat", repeat).put("passed", passed).put("turns", turns));
                        run.put("requestMeasurements", measurements.snapshot());
                        save(output, report);
                        Log.i("MatrixEvaluation", "mode=" + mode + " repeat=" + repeat + " case="
                                + scenario.id() + " passed=" + passed + " samples=" + cases.length());
                    }
                }
            }
            report.put("complete", true).put("baselineSamplingRequirementMet", true)
                    .put("incompleteReason", JSONObject.NULL).put("finishedAt", java.time.Instant.now().toString());
            save(output, report);
            Log.i("MatrixEvaluation", "completed configurations=2 repeats=2 cases=" + corpus.size());
        } catch (Exception error) {
            Log.e("MatrixEvaluation", "incomplete cause=" + error.getClass().getSimpleName());
        }
    }
    private static void validateResume(JSONObject existing, JSONObject expected) throws JSONException {
        if (existing.getBoolean("complete") || existing.getJSONArray("runs").length() > 2) {
            throw new IllegalArgumentException("only an incomplete two-mode report can resume");
        }
        for (String field : List.of("schemaVersion", "mode", "model", "protocol", "corpusSha256",
                "skillAssetsSha256", "scoringVersion", "attributionVersion", "fixtureVersion", "repetitions")) {
            if (!Objects.equals(existing.get(field), expected.get(field))) {
                throw new IllegalArgumentException("resume metadata mismatch: " + field);
            }
        }
        JSONObject previousBuild = existing.getJSONObject("build"), currentBuild = expected.getJSONObject("build");
        for (String field : List.of("commit", "worktreeDirty", "productionSourceSha256",
                "onDeviceSourceSha256", "buildDefinitionSha256")) {
            if (!Objects.equals(previousBuild.get(field), currentBuild.get(field))) {
                throw new IllegalArgumentException("resume build mismatch: " + field);
            }
        }
        JSONObject previousBudget = existing.getJSONObject("budget"), currentBudget = expected.getJSONObject("budget");
        for (String field : List.of("maxIterations", "maxToolCalls", "deadlineMillis", "maxMessageChars",
                "totalInputChars", "maxMessageCount")) {
            if (previousBudget.getLong(field) != currentBudget.getLong(field)) {
                throw new IllegalArgumentException("resume budget mismatch: " + field);
            }
        }
    }
    private static JSONArray caseCounts(JSONArray runs) throws JSONException {
        JSONArray counts = new JSONArray();
        for (int i = 0; i < runs.length(); i++) counts.put(runs.getJSONObject(i).getJSONArray("cases").length());
        return counts;
    }
    static void validateCasePrefix(JSONArray cases, List<EvaluationCase> corpus) throws JSONException {
        if (cases.length() > 2 * corpus.size()) throw new IllegalArgumentException("resume case count exceeds corpus");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject row = cases.getJSONObject(i);
            EvaluationCase expected = corpus.get(i % corpus.size());
            if (!expected.id().equals(row.getString("id")) || !expected.suite().name().equals(row.getString("suite"))
                    || row.getInt("repeat") != i / corpus.size() + 1) {
                throw new IllegalArgumentException("resume case prefix mismatch at " + i);
            }
        }
    }
    private static void waitForQuota(CancellationToken token, long delayMillis) throws InterruptedException {
        long until = android.os.SystemClock.elapsedRealtime() + delayMillis;
        while (true) {
            if (token.isCancelled()) throw new InterruptedException("evaluation cancelled while rate limited");
            long remaining = until - android.os.SystemClock.elapsedRealtime();
            if (remaining <= 0) return;
            Thread.sleep(Math.min(1_000, remaining));
        }
    }
    private static JSONArray summarize(List<EvaluationCase.Action> actions) throws JSONException {
        JSONArray array = new JSONArray();
        for (var action : actions) array.put(new JSONObject().put("kind", action.kind())
                .put("attributesSha256", Provenance.sha256(new JSONObject(action.attributes()).toString())));
        return array;
    }
    private static void save(Path output, JSONObject report) throws Exception {
        Path temporary = output.resolve("real-model-report.tmp");
        Files.write(temporary, report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.move(temporary, output.resolve("real-model-report.json"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
    private static void copyAssets(Context context, String path, Path root) throws Exception {
        String[] children = context.getAssets().list(path);
        if (children != null && children.length > 0) {
            for (String child : children) copyAssets(context, path + "/" + child, root);
        } else {
            Path target = root.resolve(path);
            Files.createDirectories(target.getParent());
            try (var input = context.getAssets().open(path)) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
