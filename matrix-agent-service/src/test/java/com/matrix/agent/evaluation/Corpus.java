package com.matrix.agent.evaluation;

import static com.matrix.agent.evaluation.EvaluationCase.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Fails closed on malformed assets before executing any model or provider. */
final class Corpus {
    static final String SCORING_VERSION = "goal-effects-v1";
    static final String FIXTURE_VERSION = "production-ports-v1";
    private Corpus() {}

    static List<EvaluationCase> load(Path path) throws IOException, org.json.JSONException {
        JSONObject root = new JSONObject(new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8));
        keys(root, "schemaVersion", "fixtureVersion", "cases");
        require(root.getInt("schemaVersion") == 1, "unsupported corpus schema");
        require(FIXTURE_VERSION.equals(root.getString("fixtureVersion")), "unsupported fixtures");
        List<EvaluationCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>(), conversations = new HashSet<>();
        for (Object value : JsonValues.values(root.getJSONArray("cases"))) {
            JSONObject row = (JSONObject) value;
            keys(row, "id", "suite", "tags", "fixtures", "turns", "scoringVersion");
            String id = row.getString("id");
            require(id.matches("[a-z][a-z0-9-]{2,80}") && ids.add(id), "invalid/duplicate id");
            require(SCORING_VERSION.equals(row.getString("scoringVersion")), "unsupported scorer: " + id);
            List<String> tags = strings(row.getJSONArray("tags"));
            require(!tags.isEmpty(), "missing coverage tags: " + id);
            List<Turn> turns = new ArrayList<>();
            for (Object turnValue : JsonValues.values(row.getJSONArray("turns"))) {
                JSONObject turn = (JSONObject) turnValue;
                keys(turn, "text", "originText", "interactive", "expireCandidates", "script", "expect");
                String text = turn.getString("text");
                require(!text.isBlank() && text.length() <= 4_000, "invalid text: " + id);
                List<ScriptStep> script = new ArrayList<>();
                for (Object stepValue : JsonValues.values(turn.getJSONArray("script"))) {
                    JSONObject step = (JSONObject) stepValue;
                    keys(step, "answer", "calls");
                    List<Action> calls = actions(step.optJSONArray("calls"));
                    String answer = step.optString("answer", "");
                    require(!answer.isBlank() || !calls.isEmpty(), "empty script step: " + id);
                    script.add(new ScriptStep(answer, calls));
                }
                JSONObject expect = turn.getJSONObject("expect");
                keys(expect, "goal", "alternatives");
                List<Alternative> alternatives = new ArrayList<>();
                for (Object item : JsonValues.values(expect.getJSONArray("alternatives"))) {
                    JSONObject alternative = (JSONObject) item;
                    keys(alternative, "effects", "observations", "answerAny", "answerNone");
                    List<Pattern> answerAny = patterns(alternative.getJSONArray("answerAny"));
                    require(!answerAny.isEmpty(), "answer criterion required: " + id);
                    alternatives.add(new Alternative(actions(alternative.getJSONArray("effects")),
                            actions(alternative.getJSONArray("observations")), answerAny,
                            patterns(alternative.getJSONArray("answerNone"))));
                }
                require(!alternatives.isEmpty(), "empty oracle: " + id);
                Goal goal = Goal.valueOf(expect.getString("goal"));
                require(goal != Goal.EFFECT || alternatives.stream().allMatch(a -> !a.effects().isEmpty()),
                        "effect goal without effect: " + id);
                require(goal == Goal.EFFECT || goal == Goal.RESOLUTION || alternatives.stream().allMatch(a -> a.effects().isEmpty()),
                        "non-effect goal permits writes: " + id);
                turns.add(new Turn(text, turn.optString("originText", text),
                        turn.optBoolean("interactive", true), turn.optBoolean("expireCandidates", false),
                        List.copyOf(script), new Expectation(goal, List.copyOf(alternatives))));
            }
            require(!turns.isEmpty() && turns.size() <= 8, "invalid turn count: " + id);
            require(conversations.add(turns.stream().map(Turn::text).toList().toString()),
                    "duplicate conversation: " + id);
            Suite suite = Suite.valueOf(row.getString("suite"));
            require(suite != Suite.MULTITURN || turns.size() > 1, "single-turn multiround case: " + id);
            validateFixtures(row.getJSONObject("fixtures"));
            cases.add(new EvaluationCase(id, suite, tags, map(row.getJSONObject("fixtures")),
                    List.copyOf(turns), SCORING_VERSION));
        }
        require(!cases.isEmpty(), "empty corpus");
        return List.copyOf(cases);
    }

    private static List<Action> actions(JSONArray values) throws org.json.JSONException {
        if (values == null) return List.of();
        List<Action> actions = new ArrayList<>();
        for (Object item : JsonValues.values(values)) {
            JSONObject value = (JSONObject) item;
            keys(value, "kind", "attributes");
            String kind = value.getString("kind");
            require(kind.matches("[a-z][a-z0-9_.]+"), "invalid action kind");
            actions.add(new Action(kind, map(value.getJSONObject("attributes"))));
        }
        return List.copyOf(actions);
    }

    static Map<String, Object> map(JSONObject object) {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonValues.keys(object).stream().sorted().forEach(key -> result.put(key, immutable(object.opt(key))));
        return Collections.unmodifiableMap(result);
    }

    private static Object immutable(Object value) {
        if (value instanceof JSONObject object) return map(object);
        if (value instanceof JSONArray array) {
            List<Object> list = new ArrayList<>();
            JsonValues.values(array).forEach(item -> list.add(immutable(item)));
            return List.copyOf(list);
        }
        require(value instanceof String || value instanceof Number || value instanceof Boolean,
                "null/unsupported fixture value");
        return value;
    }

    private static List<String> strings(JSONArray array) throws org.json.JSONException {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return List.copyOf(result);
    }

    private static List<Pattern> patterns(JSONArray array) throws org.json.JSONException {
        return strings(array).stream().map(text -> Pattern.compile(text, Pattern.DOTALL)).toList();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static void keys(JSONObject value, String... allowed) {
        require(Set.of(allowed).containsAll(JsonValues.keys(value)), "unknown corpus field");
    }

    private static void validateFixtures(JSONObject value) throws org.json.JSONException {
        keys(value, "now", "zone", "qqCandidates", "biliCandidates", "qqmusicInstalled", "bilibiliInstalled",
                "qqmusicSession", "bilibiliSession", "qqmusicState", "bilibiliState", "staleRow", "searchFailure");
        java.time.Instant.parse(value.getString("now"));
        java.time.ZoneId.of(value.getString("zone"));
        for (String key : List.of("qqmusicInstalled", "bilibiliInstalled", "qqmusicSession", "bilibiliSession", "staleRow")) {
            require(!value.has(key) || value.get(key) instanceof Boolean, "invalid fixture flag");
        }
        for (String key : List.of("qqmusicState", "bilibiliState")) {
            require(!value.has(key) || Set.of("PLAYING", "PAUSED", "STOPPED").contains(value.getString(key)), "invalid playback state");
        }
        for (String key : List.of("qqCandidates", "biliCandidates")) {
            if (!value.has(key)) continue;
            JSONArray rows = value.getJSONArray(key);
            require(rows.length() <= 8, "too many candidates");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject candidate = rows.getJSONObject(i);
                keys(candidate, "index", "title", "detail", "kind");
                require(candidate.getInt("index") == i + 1 && !candidate.getString("title").isBlank(),
                        "candidate indexes must describe actual contiguous UI rows");
                if (key.equals("qqCandidates")) require(candidate.getString("detail").contains("·"),
                        "QQ fixture detail must use the production artist/album row format");
            }
        }
    }
}
