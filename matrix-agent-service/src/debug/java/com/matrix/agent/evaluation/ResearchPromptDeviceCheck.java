package com.matrix.agent.evaluation;

import static com.matrix.agent.api.schedule.ScheduleCodes.ONCE;
import static com.matrix.agent.api.schedule.ScheduleCodes.SUCCEEDED;
import static com.matrix.agent.api.schedule.ScheduleCodes.WITHIN_GRACE;
import static com.matrix.agent.api.schedule.ScheduleCodes.WORKFLOW;

import com.matrix.agent.api.schedule.ScheduleAction;
import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.api.schedule.ScheduleTiming;
import com.matrix.agent.data.schedule.ScheduleRunEntity;
import com.matrix.agent.data.schedule.ScheduleStepEntity;
import com.matrix.agent.schedule.domain.ScheduleCodec;
import com.matrix.agent.schedule.workflow.ResearchWorkflow;
import com.matrix.agent.task.scheduler.PreparedAutomaticTask;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Exercises the production research prompt builder on Android without a model or user plan. */
final class ResearchPromptDeviceCheck {
    private ResearchPromptDeviceCheck() { }

    static JSONObject verify() throws Exception {
        long now = System.currentTimeMillis();
        JSONArray samples = new JSONArray();
        for (String stress : List.of("&", "汉")) {
            String query = stress.repeat(300);
            ScheduleRunEntity run = new ScheduleRunEntity();
            run.runId = "synthetic-research-prompt";
            run.dataEpoch = 1;
            run.expiresAt = now + ResearchWorkflow.DEFAULT_WINDOW_MILLIS;
            run.specJson = ScheduleCodec.spec(new ScheduleSpec("synthetic research",
                    new ScheduleTiming(ONCE, "UTC", now, 0, "", 0, "", "", false, "", 0),
                    new ScheduleAction(WORKFLOW, "compare evidence", ResearchWorkflow.ID, 1,
                            new JSONObject().put("query", query).toString(), List.of("web.search"), true, false),
                    ResearchWorkflow.DEFAULT_WINDOW_MILLIS, WITHIN_GRACE));

            List<ScheduleStepEntity> steps = new ArrayList<>();
            List<String> sourceIds = new ArrayList<>();
            for (String source : ResearchWorkflow.SOURCES) {
                ScheduleStepEntity step = sourceStep(run, source, query, stress, now);
                steps.add(step);
                sourceIds.add(ResearchWorkflow.evidence(run, step, now).getJSONArray("sources")
                        .getJSONObject(0).getString("id"));
                ScheduleStepEntity review = new ScheduleStepEntity();
                review.stepId = "review_" + source;
                review.state = SUCCEEDED;
                review.result = stress.repeat(400);
                steps.add(review);
            }

            String goal = ResearchWorkflow.goal(run, "summary", steps, now);
            int utf8Bytes = goal.getBytes(StandardCharsets.UTF_8).length;
            require(goal.length() <= PreparedAutomaticTask.MAX_TEXT_CHARS, "character budget");
            require(utf8Bytes <= PreparedAutomaticTask.MAX_TEXT_UTF8_BYTES, "UTF-8 budget");
            for (String id : sourceIds) require(goal.contains("[" + id + "]"), "source lost from prompt");
            require(goal.contains(stress.equals("&") ? "&amp;" : "汉"), "escaped content lost");
            samples.put(new JSONObject().put("stress", stress.equals("&") ? "HTML_ESCAPE" : "MULTIBYTE_UTF8")
                    .put("characters", goal.length()).put("utf8Bytes", utf8Bytes)
                    .put("independentSources", sourceIds.size()));
        }
        return new JSONObject().put("samples", samples).put("userPlanTouched", false);
    }

    private static ScheduleStepEntity sourceStep(ScheduleRunEntity run, String source, String query,
            String stress, long now) throws Exception {
        ScheduleStepEntity step = new ScheduleStepEntity();
        step.stepId = source;
        step.state = SUCCEEDED;
        step.inputJson = new JSONObject().put("query", query).put("source", source).toString();
        JSONArray sources = new JSONArray();
        for (int i = 0; i < 2; i++) {
            String url = switch (source) {
                case "wikipedia" -> "https://en.wikipedia.org/wiki/Synthetic_research_" + i;
                case "crossref" -> "https://doi.org/10.1234/synthetic-research-" + i;
                case "arxiv" -> "https://arxiv.org/abs/2005.1140" + i;
                default -> throw new IllegalArgumentException("unknown research source");
            };
            sources.put(new JSONObject().put("id", "src_" + ScheduleCodec.digest(url).substring(0, 16))
                    .put("url", url).put("title", stress.repeat(180))
                    .put("snippet", stress.repeat(700)).put("evidenceKind", "AUTHOR_ABSTRACT")
                    .put("fullTextRead", false));
        }
        JSONObject payload = new JSONObject().put("version", 1).put("source", source)
                .put("sources", sources).put("count", sources.length())
                .put("coverage", "SNIPPETS_AND_METADATA_ONLY")
                .put("queryDigest", ScheduleCodec.digest(query)).put("retrievedAt", now);
        step.outputJson = ResearchWorkflow.checkpoint(run, step, payload.toString(), now);
        return step;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
