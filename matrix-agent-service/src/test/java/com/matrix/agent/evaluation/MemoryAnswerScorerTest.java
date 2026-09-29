package com.matrix.agent.evaluation;

import com.matrix.agent.task.*;
import com.matrix.agent.task.tool.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class MemoryAnswerScorerTest {
    private AgentOutcome outcome(String answer, List<ToolResult> results) {
        return new AgentOutcome("synthetic", TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL,
                new Trajectory(), 1, results, answer);
    }
    @Test public void speculativeCandidateInAbstentionIsNotCorrect() {
        var score = MemoryAnswerScorer.score(outcome("还没有保存过您的偏好。比如咖啡/豆浆/温水，可以告诉我。", List.of()), "fact.drink", "豆浆");
        assertTrue(score.expectedTermPresent()); assertTrue(score.abstained()); assertFalse(score.correct());
    }
    @Test public void correctClaimNeedsSuccessfulReadOfExpectedFact() {
        assertFalse(MemoryAnswerScorer.score(outcome("您喝豆浆。", List.of()), "fact.drink", "豆浆").correct());
        var read = new ToolResult(ToolResult.Status.SUCCESS, "memory.semantic.get", "found",
                Map.of("fact.drink", "豆浆"), true, 1);
        assertTrue(MemoryAnswerScorer.score(outcome("您喝豆浆。", List.of(read)), "fact.drink", "豆浆").correct());
        assertFalse(MemoryAnswerScorer.score(outcome("您喝豆浆。", List.of(read)), "fact.other", "豆浆").correct());
    }
}
