package com.matrix.agent.task.prompt;

import android.util.Log;

import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.task.identity.ActorUsers;
import com.matrix.agent.task.identity.AgentRequest;

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

    public PromptContextAssembler(MemoryRecaller memoryRecaller, PromptBuilder promptBuilder) {
        this.memoryRecaller = memoryRecaller;
        this.promptBuilder = promptBuilder;
    }

    public String build(AgentRequest request) {
        List<MemorySnippet> recalled = recallSafely(request);
        if (promptBuilder != null) {
            try {
                return DefaultPromptBuilder.join(promptBuilder.buildSystemPrompt(request, recalled));
            } catch (Exception error) {
                Log.w(TAG, "[Prompt] builder failed, fallback to base req="
                        + request.getRequestId() + " cause=" + error.getClass().getSimpleName());
            }
        }
        return fallbackPrompt(request, recalled);
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
