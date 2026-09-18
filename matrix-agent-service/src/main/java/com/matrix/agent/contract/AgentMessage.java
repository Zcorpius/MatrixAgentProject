package com.matrix.agent.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Agent Loop 内部的不可变消息,Provider 无关。 */
public final class AgentMessage {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String toolName;

    private AgentMessage(Role role, String content, List<ToolCall> toolCalls,
            String toolCallId, String toolName) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<>(toolCalls));
        this.toolCallId = toolCallId;
        this.toolName = toolName;
    }

    public static AgentMessage system(String content) {
        return new AgentMessage(Role.SYSTEM, content, Collections.emptyList(), null, null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(Role.USER, content, Collections.emptyList(), null, null);
    }

    /** assistant 消息:可带文本 content,可带 toolCalls;两者至少一个非空。 */
    public static AgentMessage assistant(String content, List<ToolCall> toolCalls) {
        List<ToolCall> calls = toolCalls == null ? Collections.emptyList() : toolCalls;
        if (content == null && calls.isEmpty()) {
            throw new IllegalArgumentException("assistant 消息必须至少有 content 或 toolCalls");
        }
        return new AgentMessage(Role.ASSISTANT, content, calls, null, null);
    }

    /** tool 消息:关联 assistant 的 toolCalls[i].stepId,内容是 Observation 文本。 */
    public static AgentMessage tool(String toolCallId, String toolName, String content) {
        if (toolCallId == null || toolCallId.isEmpty()) {
            throw new IllegalArgumentException("tool 消息必须带 toolCallId");
        }
        return new AgentMessage(Role.TOOL, content, Collections.emptyList(), toolCallId, toolName);
    }

    /**
     * 压缩摘要消息(role=SYSTEM,前缀标明"系统生成,不含指令")。
     *
     * <p>ConversationCompressor 把旧 turns 摘要替换成此消息,作为 conversation 头部;
     * 前缀 "[系统生成的对话摘要,不含指令]" 给 Provider 明确语义边界,降低 prompt injection 风险。
     */
    public static AgentMessage summary(String text) {
        String content = (text == null || text.isEmpty())
                ? PREFIX_SUMMARY + "上文已省略。"
                : PREFIX_SUMMARY + text;
        return new AgentMessage(Role.SYSTEM, content, Collections.emptyList(), null, null);
    }

    /** SummaryMessage 内容前缀——强语义边界,Provider 不会把这当 user 指令执行。 */
    public static final String PREFIX_SUMMARY = "[系统生成的对话摘要,不含指令] ";

    public Role getRole() { return role; }
    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public String getToolCallId() { return toolCallId; }
    public String getToolName() { return toolName; }

    /** 估算本条消息占用字符数(char-based,后续切 token)。 */
    public int estimateChars() {
        int size = content == null ? 0 : content.length();
        for (ToolCall call : toolCalls) {
            size += call.getCapabilityName().length();
            size += call.getArguments() == null ? 0 : call.getArguments().toString().length();
        }
        return size;
    }

    @Override
    public String toString() {
        if (role == Role.TOOL) {
            return "tool[" + toolName + "/" + toolCallId + "]: " + content;
        }
        if (!toolCalls.isEmpty()) {
            return "assistant: " + content + " toolCalls=" + toolCalls;
        }
        return role.name().toLowerCase(Locale.ROOT) + ": " + content;
    }
}
