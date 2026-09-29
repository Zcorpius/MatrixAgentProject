package com.matrix.agent.evaluation;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Versioned test data. Scripts and expectations never enter the real model's context. */
record EvaluationCase(String id, Suite suite, List<String> tags, Map<String, Object> fixtures,
        List<Turn> turns, String scoringVersion) {
    enum Suite { MEDIA, SCHEDULE, MULTITURN }
    enum Goal { EFFECT, READ, CLARIFICATION, REJECTION, RESOLUTION }

    record Turn(String text, String originText, boolean interactive, boolean expireCandidates,
            List<ScriptStep> script, Expectation expect) {}
    record ScriptStep(String answer, List<Action> calls) {}
    record Action(String kind, Map<String, Object> attributes) {}
    record Expectation(Goal goal, List<Alternative> alternatives) {}
    /** All effects must be allowed; required effects/observations are unordered multisets. */
    record Alternative(List<Action> effects, List<Action> observations,
            List<Pattern> answerAny, List<Pattern> answerNone) {}
}
