package com.matrix.agent.evaluation;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

public final class CorpusTest {
    private static final Path CORPUS = Path.of("src/test/resources/evaluation/corpus-v1.json");

    @Test public void initialCorpusHasDisjointReviewedDenominators() throws Exception {
        var cases = Corpus.load(CORPUS);
        Map<EvaluationCase.Suite, Long> counts = cases.stream().collect(Collectors.groupingBy(
                EvaluationCase::suite, Collectors.counting()));
        assertEquals(Map.of(EvaluationCase.Suite.MEDIA, 60L, EvaluationCase.Suite.SCHEDULE, 30L,
                EvaluationCase.Suite.MULTITURN, 20L), counts);
        assertEquals(110, cases.stream().map(EvaluationCase::id).distinct().count());
    }

    @Test public void unsupportedScoringAndDuplicateCasesFailBeforeExecution() throws Exception {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(CORPUS), StandardCharsets.UTF_8));
        root.getJSONArray("cases").getJSONObject(0).put("scoringVersion", "future-unknown");
        rejects(root);
        root.getJSONArray("cases").getJSONObject(0).put("scoringVersion", Corpus.SCORING_VERSION);
        root.getJSONArray("cases").put(root.getJSONArray("cases").getJSONObject(0));
        rejects(root);
    }

    @Test public void effectGoalCannotBeScoredUsingOnlyAPromisedAnswer() throws Exception {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(CORPUS), StandardCharsets.UTF_8));
        root.getJSONArray("cases").getJSONObject(0).getJSONArray("turns").getJSONObject(0)
                .getJSONObject("expect").put("goal", "EFFECT");
        rejects(root);
    }

    private static void rejects(JSONObject root) throws Exception {
        Path file = Files.createTempFile("matrix-invalid-corpus", ".json");
        try {
            Files.write(file, root.toString().getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalArgumentException.class, () -> Corpus.load(file));
        } finally { Files.deleteIfExists(file); }
    }
}
