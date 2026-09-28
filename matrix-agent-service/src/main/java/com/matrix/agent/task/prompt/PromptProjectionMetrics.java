package com.matrix.agent.task.prompt;

import com.matrix.agent.data.memory.MemoryKeyCatalog;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemorySnippet;
import java.util.List;

/** Counts only; never records recalled values, keys or prompt text in diagnostics. */
public record PromptProjectionMetrics(int eligibleSemanticKeys, int retainedSemanticKeys,
        boolean memoryRemoved, boolean skillSelected, boolean skillRetained, BudgetBranch branch) {
    public enum BudgetBranch { NO_SKILL, FULL, DROP_MEMORY, BASE_ONLY, TRIM_BASE }
    static PromptProjectionMetrics measure(List<MemorySnippet> recalled, String base, String skill,
            String result, BudgetBranch branch) {
        int eligible = 0, retained = 0;
        int start = result.indexOf("<memory_context>");
        int end = result.indexOf("</memory_context>", Math.max(0, start));
        String memory = start >= 0 ? result.substring(start, end < start ? result.length() : end) : "";
        for (MemorySnippet snippet : recalled) {
            if (snippet.getLayer() != MemoryLayer.SEMANTIC) continue;
            String key = MemoryKeyCatalog.promptKey(snippet.getLayer(), snippet.getKey());
            if (key == null) continue;
            eligible++;
            String line = "\n- [semantic] " + key;
            int at = memory.indexOf(line);
            if (at >= 0 && at + line.length() < memory.length()
                    && " :\n".indexOf(memory.charAt(at + line.length())) >= 0) retained++;
        }
        boolean selected = skill != null && !skill.isEmpty();
        return new PromptProjectionMetrics(eligible, retained,
                base.contains("<memory_context>") && !result.contains("<memory_context>"),
                selected, selected && result.endsWith(skill), branch);
    }
}
