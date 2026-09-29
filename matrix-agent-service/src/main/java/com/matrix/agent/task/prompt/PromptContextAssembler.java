package com.matrix.agent.task.prompt;

import android.util.Log;

import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.skill.SkillSelector;
import com.matrix.agent.task.redact.ModelSanitizer;

import java.util.Collections;
import java.util.List;

/**
 * 为一次任务组装系统 Prompt 及可选的记忆上下文。
 *
 * <p>记忆是增强能力：召回与自定义 PromptBuilder 任一失败都只降级为基础 Prompt，
 * 不得阻断任务执行。该策略以前散落在 AgentEngine 中。
 */
public final class PromptContextAssembler {
    private static final String TAG = "MatrixAgent";
    private static final int RECALL_LIMIT = 8;

    private final MemoryRecaller memoryRecaller;
    private final PromptBuilder promptBuilder;
    private final SkillSelector skillSelector;
    private final int maxMessageChars;
    private final com.matrix.agent.failure.FailureLessonRecaller lessons;
    private final java.util.function.Consumer<PromptProjectionMetrics> metrics;

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder) {
        this(memoryRecaller, promptBuilder, null);
    }

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder,
            SkillSelector skillSelector) {
        this(memoryRecaller, promptBuilder, skillSelector, Integer.MAX_VALUE);
    }

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder,
            SkillSelector skillSelector, int maxMessageChars) {
        this(memoryRecaller, promptBuilder, skillSelector, maxMessageChars, null);
    }

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder,
            SkillSelector skillSelector, int maxMessageChars,
            com.matrix.agent.failure.FailureLessonRecaller lessons) {
        this(memoryRecaller, promptBuilder, skillSelector, maxMessageChars, lessons,
                metric -> Log.d(TAG, "[PromptProjection] " + metric));
    }

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder,
            SkillSelector skillSelector, int maxMessageChars,
            com.matrix.agent.failure.FailureLessonRecaller lessons,
            java.util.function.Consumer<PromptProjectionMetrics> metrics) {
        this.metrics = java.util.Objects.requireNonNull(metrics);
        if (maxMessageChars <= 0) throw new IllegalArgumentException("maxMessageChars 必须大于 0");
        this.memoryRecaller = memoryRecaller;
        this.promptBuilder = promptBuilder;
        this.skillSelector = skillSelector;
        this.maxMessageChars = maxMessageChars;
        this.lessons = lessons;
    }

    public String build(AgentRequest request) {
        List<MemorySnippet> recalled = recallSafely(request);
        if (promptBuilder != null) {
            try {
                return withSkill(request,
                        DefaultPromptBuilder.join(promptBuilder.buildSystemPrompt(request, recalled)), recalled);
            } catch (Exception error) {
                Log.w(TAG, "[Prompt] builder failed, fallback to base req="
                        + request.getRequestId() + " cause=" + error.getClass().getSimpleName());
            }
        }
        return withSkill(request, fallbackPrompt(request, recalled), recalled);
    }

    private String withSkill(AgentRequest request, String base, List<MemorySnippet> recalled) {
        String skill = "";
        try { skill = skillSelector == null ? "" : skillSelector.promptFor(request); }
        catch (RuntimeException error) {
            Log.w(TAG, "[Prompt] skill selection unavailable req=" + request.getRequestId()
                    + " cause=" + error.getClass().getSimpleName());
        }
        Assembly assembly = assemble(base, skill, maxMessageChars);
        String result = assembly.text();
        try { metrics.accept(PromptProjectionMetrics.measure(recalled, base, skill, result, assembly.branch())); }
        catch (RuntimeException ignored) { /* Observation never changes the prompt. */ }
        // Advice receives only spare capacity; it never evicts the skill, rules or recalled memory.
        if (lessons != null) try {
            String advice = lessons.project(request);
            if (advice.length() <= 512 && (long) result.length() + advice.length() <= maxMessageChars) result += advice;
        } catch (RuntimeException ignored) { }
        return result;
    }

    static String appendSkillWithinBudget(String base, String skill, int limit) {
        return assemble(base, skill, limit).text();
    }

    record Assembly(String text, PromptProjectionMetrics.BudgetBranch branch) { }
    static Assembly assemble(String base, String skill, int limit) {
        if (skill == null || skill.isEmpty()) return new Assembly(ModelSanitizer.truncateWithSuffix(base, limit),
                PromptProjectionMetrics.BudgetBranch.NO_SKILL);
        if ((long) base.length() + skill.length() <= limit) return new Assembly(base + skill,
                PromptProjectionMetrics.BudgetBranch.FULL);
        int memoryStart = base.indexOf("\n已召回的 Memory");
        if (memoryStart >= 0 && (long) memoryStart + skill.length() <= limit) {
            return new Assembly(base.substring(0, memoryStart) + skill, PromptProjectionMetrics.BudgetBranch.DROP_MEMORY);
        }
        int baseRoom = limit - skill.length();
        if (baseRoom < Math.min(base.length(), 256)) {
            return new Assembly(ModelSanitizer.truncateWithSuffix(base, limit), PromptProjectionMetrics.BudgetBranch.BASE_ONLY);
        }
        return new Assembly(ModelSanitizer.truncateWithSuffix(base, baseRoom) + skill,
                PromptProjectionMetrics.BudgetBranch.TRIM_BASE);
    }
    private List<MemorySnippet> recallSafely(AgentRequest request) {
        if (memoryRecaller == null) return Collections.emptyList();
        try {
            MemoryScope scope = new MemoryScope(ActorUsers.userIdOf(request), request.getOccupantZone());
            List<MemorySnippet> recalled = memoryRecaller.recall(scope, request.getSessionId(),
                    request.getText(), RECALL_LIMIT);
            return recalled == null ? Collections.emptyList() : recalled;
        } catch (Exception error) {
            Log.w(TAG, "[Prompt] memory recall failed, fallback to base req="
                    + request.getRequestId() + " cause=" + error.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private static String fallbackPrompt(AgentRequest request, List<MemorySnippet> recalled) {
        String base = String.format(DefaultPromptBuilder.BASE_TEMPLATE,
                request.getActor(), request.getOccupantZone(), request.getInputSource());
        return recalled == null || recalled.isEmpty()
                ? base : base + DefaultPromptBuilder.formatRecalledMemory(recalled);
    }
}
