package com.matrix.agent.evaluation;

import com.matrix.agent.task.AgentOutcome;
import java.util.Objects;
import java.util.regex.Pattern;

/** Fixed synthetic-answer rubric: mentioning a candidate in an abstention is not a correct fact. */
public final class MemoryAnswerScorer {
    public static final String VERSION = "memory-answer-evidence-v2";
    private static final Pattern ABSTENTION = Pattern.compile("还没有|没有保存|无法回答|查不到|不清楚|不知道|没有.{0,24}记录");
    public record Score(boolean correct, boolean expectedTermPresent, boolean expectedFactRead, boolean abstained) { }
    private MemoryAnswerScorer() { }

    public static Score score(AgentOutcome outcome, String expectedKey, String answerPattern) {
        String answer = Objects.toString(outcome.getFinalAssistantText(), "");
        boolean matched = Pattern.compile(answerPattern).matcher(answer).find();
        boolean abstained = ABSTENTION.matcher(answer).find();
        boolean read = !expectedKey.isEmpty() && outcome.getInternalResults().stream().anyMatch(result ->
                "memory.semantic.get".equals(result.getCapabilityName()) && result.isSuccess()
                        && result.isVerified() && result.getObservedState().containsKey(expectedKey));
        return new Score(expectedKey.isEmpty() ? matched : matched && read && !abstained, matched, read, abstained);
    }
}
