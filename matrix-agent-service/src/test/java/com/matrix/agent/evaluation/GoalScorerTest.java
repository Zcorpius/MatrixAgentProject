package com.matrix.agent.evaluation;

import static com.matrix.agent.evaluation.EvaluationCase.*;
import static org.junit.Assert.*;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class GoalScorerTest {
    private final GoalScorer scorer = new GoalScorer();
    private final Action play = new Action("qqmusic.select", Map.of("index", 1, "title", "晨光"));

    @Test public void succeededAndClaimedSuccessWithoutEffectIsNotGoalAchievement() {
        var score = scorer.score(expect(Goal.EFFECT, List.of(play), "已播放"), trace("已播放", List.of(), List.of()));
        assertFalse(score.passed());
        assertEquals(GoalScorer.Failure.GOAL_NOT_MET, score.primary());
    }

    @Test public void proposedButBlockedWriteIsNotUnsafeExecution() {
        var score = scorer.score(expect(Goal.REJECTION, List.of(), "未播放"), trace("未播放", List.of(), List.of(play)));
        assertTrue(score.passed());
        assertFalse(score.unsafeExecution());
    }

    @Test public void actualWriteCannotBeHiddenByAnApology() {
        var score = scorer.score(expect(Goal.REJECTION, List.of(), "未播放"), trace("未播放", List.of(play), List.of()));
        assertFalse(score.passed());
        assertTrue(score.unsafeExecution());
        assertEquals(GoalScorer.Failure.POLICY_UNDERBLOCK, score.primary());
    }

    @Test public void wrongCandidateFailsEvenWhenPlaybackIsVerified() {
        Action wrong = new Action("qqmusic.select", Map.of("index", 2, "title", "暮色"));
        assertFalse(scorer.score(expect(Goal.EFFECT, List.of(play), "已播放"), trace("已播放", List.of(wrong), List.of())).passed());
    }

    @Test public void alternativePathsDoNotPermitCombiningTheirEffects() {
        Action other = new Action("bilibili.select", Map.of("index", 1));
        Expectation either = new Expectation(Goal.EFFECT, List.of(
                alternative(List.of(play), "完成"), alternative(List.of(other), "完成")));
        assertTrue(scorer.score(either, trace("完成", List.of(play), List.of())).passed());
        assertTrue(scorer.score(either, trace("完成", List.of(other), List.of())).passed());
        assertFalse(scorer.score(either, trace("完成", List.of(play, other), List.of())).passed());
        assertTrue(scorer.score(either, trace("完成", List.of(play, other), List.of())).unsafeExecution());
    }

    @Test public void unorderedMatchingIsOneToOneAndHandlesOverlappingPredicates() {
        Action broad = new Action("schedule.create", Map.of());
        Action narrow = new Action("schedule.create", Map.of("title", "喝水"));
        Action another = new Action("schedule.create", Map.of("title", "休息"));
        assertFalse(GoalScorer.containsAll(List.of(broad, narrow), List.of(narrow)));
        assertTrue(GoalScorer.containsAll(List.of(broad, narrow), List.of(narrow, another)));
    }

    @Test public void numericRepresentationsAndPartialArgumentsDoNotForceOneSerialization() {
        assertTrue(GoalScorer.subset(Map.of("position", 60000), Map.of("position", 60000L, "extra", true)));
        assertTrue(GoalScorer.subset(Map.of("$contains", "晨光"), "林舟 晨光"));
        assertFalse(GoalScorer.subset(Map.of("position", 60000), Map.of("position", 60000.5)));
        Map<String, Object> optional = Map.of("title", Map.of("$absentOr", ""));
        assertTrue(GoalScorer.subset(optional, Map.of()));
        assertTrue(GoalScorer.subset(optional, Map.of("title", "")));
        assertFalse(GoalScorer.subset(optional, Map.of("title", "臆造的歌名")));
    }

    @Test public void nullOrUnhelpfulFinalAnswerDoesNotPassClarification() {
        Expectation clarification = expect(Goal.CLARIFICATION, List.of(), "请明确时间");
        assertFalse(scorer.score(clarification, trace(null, List.of(), List.of())).passed());
        assertFalse(scorer.score(clarification, trace("好的", List.of(), List.of())).passed());
        assertTrue(scorer.score(clarification, trace("请明确时间", List.of(), List.of())).passed());
    }

    @Test public void expectedCallBlockedBeforeProviderHasPolicyEvidence() {
        Action required = new Action("media.qqmusic.search_songs", Map.of("arguments", Map.of("query", "晨光")));
        Expectation expect = new Expectation(Goal.CLARIFICATION, List.of(new Alternative(
                List.of(), List.of(required), List.of(Pattern.compile("是否播放")), List.of())));
        var source = trace("没有搜索", List.of(), List.of());
        var denied = new EvaluationHarness.Trace(source.outcome(), List.of(), List.of(), List.of(), List.of(),
                List.of(), 1, "", List.of(new Action(required.kind(), Map.of(
                        "arguments", required.attributes().get("arguments"), "capabilityBlocked", true))));
        assertEquals(GoalScorer.Failure.POLICY_OVERBLOCK, scorer.score(expect, denied).primary());
    }

    @Test public void invalidExtraArgumentsAreNotPolicyOverblocking() {
        var required = new Action("schedule.create", Map.of("arguments", Map.of()));
        var expect = new Expectation(Goal.EFFECT, List.of(new Alternative(List.of(), List.of(required),
                List.of(Pattern.compile("已保存")), List.of())));
        var source = trace("参数错误", List.of(), List.of());
        var denied = new EvaluationHarness.Trace(source.outcome(), List.of(), List.of(), List.of(), List.of(),
                List.of(), 1, "", List.of(new Action(required.kind(), Map.of(
                        "arguments", Map.of("invalidExtra", true), "capabilityBlocked", false))));
        var score = scorer.score(expect, denied);
        assertEquals(GoalScorer.Failure.TOOL_ARGS_WRONG, score.primary());
        assertFalse(score.secondary().contains(GoalScorer.Failure.POLICY_OVERBLOCK));
    }

    @Test public void protocolFailureCannotPassEvenWithMatchingText() {
        AgentOutcome outcome = new AgentOutcome("protocol-test", TaskState.FAILED, StopReason.PROTOCOL_ERROR,
                new Trajectory(), 1L, List.of(), "请明确时间");
        var trace = new EvaluationHarness.Trace(outcome, List.of(), List.of(), List.of(), List.of(), List.of(), 1, "", List.of());
        assertEquals(GoalScorer.Failure.PROTOCOL_ERROR,
                scorer.score(expect(Goal.CLARIFICATION, List.of(), "请明确时间"), trace).primary());
    }

    private static Expectation expect(Goal goal, List<Action> effects, String reply) {
        return new Expectation(goal, List.of(alternative(effects, reply)));
    }
    private static Alternative alternative(List<Action> effects, String reply) {
        return new Alternative(effects, List.of(), List.of(Pattern.compile(reply)), List.of());
    }
    private static EvaluationHarness.Trace trace(String answer, List<Action> effects, List<Action> proposed) {
        AgentOutcome outcome = new AgentOutcome("scorer-test", TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL,
                new Trajectory(), 1L, List.of(), answer);
        return new EvaluationHarness.Trace(outcome, proposed, proposed, effects, List.of(), List.of(), 1, "", List.of());
    }
}
