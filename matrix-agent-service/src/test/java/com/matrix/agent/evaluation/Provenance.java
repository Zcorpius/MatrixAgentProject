package com.matrix.agent.evaluation;

import com.matrix.agent.task.AgentBudget;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class Provenance {
    private Provenance() {}

    static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static JSONObject collect(Path project, Path corpus, String mode) throws IOException, InterruptedException, org.json.JSONException {
        AgentBudget budget = new AgentBudget();
        return new JSONObject().put("schemaVersion", 1).put("mode", mode)
                .put("commit", git(project, "rev-parse", "HEAD").strip())
                .put("worktreeDirty", !git(project, "status", "--porcelain").isBlank())
                .put("corpusSha256", sha256(Files.readAllBytes(corpus)))
                .put("scoringVersion", Corpus.SCORING_VERSION).put("attributionVersion", GoalScorer.ATTRIBUTION_VERSION).put("fixtureVersion", Corpus.FIXTURE_VERSION)
                .put("productionSourceSha256", treeHash(project.resolve("src/main/java")))
                .put("skillAssetsSha256", treeHash(project.resolve("src/main/assets/skills")))
                .put("evaluatorSourceSha256", treeHash(project.resolve("src/test/java/com/matrix/agent/evaluation")))
                .put("fixtureSourceSha256", sha256(
                        sha256(Files.readAllBytes(project.resolve("src/test/java/com/matrix/agent/schedule/store/ScheduleStoreFixture.java")))
                                + sha256(Files.readAllBytes(project.resolve("src/test/java/com/matrix/agent/task/skill/EvaluationSkills.java")))))
                .put("javaVersion", System.getProperty("java.version"))
                .put("budget", new JSONObject().put("maxIterations", budget.getMaxIterations())
                        .put("maxToolCalls", budget.getMaxToolCalls()).put("deadlineMillis", budget.getTotalDeadlineMillis())
                        .put("maxMessageChars", budget.getMaxMessageChars()).put("totalInputChars", budget.getTotalInputChars())
                        .put("maxMessageCount", budget.getMaxMessageCount()))
                .put("history", "completed user/assistant pairs; empty initial memory")
                .put("answerScoring", "versioned structural/regex rubric; real-model failures require human review")
                .put("cost", JSONObject.NULL).put("costStatus", "unknown");
    }

    static String treeHash(Path directory) throws IOException {
        StringBuilder manifest = new StringBuilder();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                manifest.append(directory.relativize(path)).append('\0')
                        .append(sha256(Files.readAllBytes(path))).append('\n');
            }
        }
        return sha256(manifest.toString());
    }

    private static String git(Path directory, String... arguments) throws IOException, InterruptedException {
        var command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("cannot resolve evaluation git provenance");
        return output;
    }
}
