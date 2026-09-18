package com.matrix.agent.task.prompt;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.data.memory.MemorySnippet;

import java.util.List;

/**
 * AgentEngine.buildSystemPrompt / LlmPlanner.userPrompt 的装配契约。
 *
 * <p>不强制接入 AgentEngine 主路径——仅提供能力,上下文压缩 / zone-aware
 * ToolSchemaView 在不改 AgentEngine 的前提下扩展(替换旧版硬编码文案)。
 *
 * <p>实现 {@link DefaultPromptBuilder} 复用旧版文案 + 拼装 recalled memory 段,
 * 与 AgentEngine.buildSystemPrompt 当前行为等价(289 测试兼容)。
 */
public interface PromptBuilder {
    /**
     * 装配 system prompt 的有序段落。
     *
     * <p>返回 List 而非拼接字符串:caller 可按段类型独立处理(压缩、截断、redact、token 计费)。
     * caller 自行 join("\n");PromptComposer 加入后做预算分配。
     *
     * @param request 当前 AgentRequest(用于 zone / actor hint)
     * @param recalledMemory 已召回的 Memory snippet(可能为空)
     */
    List<PromptSegment> buildSystemPrompt(AgentRequest request, List<MemorySnippet> recalledMemory);
}
