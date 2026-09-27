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

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder) {
        this(memoryRecaller, promptBuilder, null);
    }

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder,
            SkillSelector skillSelector) {
        this(memoryRecaller, promptBuilder, skillSelector, Integer.MAX_VALUE);
    }

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder,
            SkillSelector skillSelector, int maxMessageChars) {
        if (maxMessageChars <= 0) throw new IllegalArgumentException("maxMessageChars 必须大于 0");
        this.memoryRecaller = memoryRecaller;
        this.promptBuilder = promptBuilder;
        this.skillSelector = skillSelector;
        this.maxMessageChars = maxMessageChars;
    }

    public String build(AgentRequest request) {
        List<MemorySnippet> recalled = recallSafely(request);
        if (promptBuilder != null) {
            try {
                return withSkill(request,
                        DefaultPromptBuilder.join(promptBuilder.buildSystemPrompt(request, recalled)));
            } catch (Exception error) {
                Log.w(TAG, "[Prompt] builder failed, fallback to base req="
                        + request.getRequestId() + " cause=" + error.getClass().getSimpleName());
            }
        }
        return withSkill(request, fallbackPrompt(request, recalled));
    }

    private String withSkill(AgentRequest request, String base) {
        if (skillSelector == null) return base;
        try {
            return appendSkillWithinBudget(base, skillSelector.promptFor(request),
                    maxMessageChars);
        } catch (RuntimeException error) {
            Log.w(TAG, "[Prompt] skill selection unavailable req=" + request.getRequestId()
                    + " cause=" + error.getClass().getSimpleName());
            return base;
        }
    }

    static String appendSkillWithinBudget(String base, String skill, int limit) {
        if (skill == null || skill.isEmpty()) return base;
        if ((long) base.length() + skill.length() <= limit) return base + skill;
        int memoryStart = base.indexOf("\n已召回的 Memory");
        if (memoryStart >= 0 && (long) memoryStart + skill.length() <= limit) {
            return base.substring(0, memoryStart) + skill;
        }
        int baseRoom = limit - skill.length();
        // A tiny custom budget cannot fit the signed skill and core system instructions.
        // Keep the base prompt intact up to the engine limit instead of a partial skill.
        if (baseRoom < Math.min(base.length(), 256)) {
            return ModelSanitizer.truncateWithSuffix(base, limit);
        }
        return ModelSanitizer.truncateWithSuffix(base, baseRoom) + skill;
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
