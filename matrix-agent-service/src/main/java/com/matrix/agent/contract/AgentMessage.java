package com.matrix.agent.contract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Agent Loop 内部的不可变消息,Provider 无关。 */
public final class AgentMessage {
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }

    public record ReadReceipt(String callId, String capability, String argumentsDigest) { }
    private final boolean verifiedRead;
    private final List<ReadReceipt> readReceipts;
    private final Role role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;
    private final String toolName;

    private AgentMessage(Role role, String content, List<ToolCall> toolCalls,
            String toolCallId, String toolName) {
        this(role, content, toolCalls, toolCallId, toolName, false, List.of());
    }
    private AgentMessage(Role role, String content, List<ToolCall> toolCalls,
            String toolCallId, String toolName, boolean verifiedRead, List<ReadReceipt> receipts) {
        this.verifiedRead = verifiedRead; this.readReceipts = List.copyOf(receipts);
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

    /** Host-only metadata; never inferred from model-authored or provider-authored text. */
    public static AgentMessage verifiedReadTool(String callId, String name, String content) {
        if (!"web.search".equals(name)) throw new IllegalArgumentException("unsupported compactable read");
        if (callId == null || callId.isBlank()) throw new IllegalArgumentException("read call ID required");
        return new AgentMessage(Role.TOOL, content, List.of(), callId, name, true, List.of());
    }
    public boolean isVerifiedRead() { return verifiedRead; }
    public List<ReadReceipt> getReadReceipts() { return readReceipts; }
    public static AgentMessage readReceipts(List<ReadReceipt> receipts) {
        if (receipts.isEmpty() || receipts.size() > 40) throw new IllegalArgumentException("receipt bound");
        List<ReadReceipt> frozen = List.copyOf(receipts);
        String content = PREFIX_SUMMARY + "只读检索回执：SUCCESS verified=true 仅表示响应格式与来源通过检查，不代表第三方断言已证实。"
                + "历史参数与正文已省略；需要引用或事实时必须重新检索。不得根据回执推断内容。\n" + frozen;
        return new AgentMessage(Role.SYSTEM, content, List.of(), null, null, false, frozen);
    }
    public AgentMessage withContent(String replacement) {
        return new AgentMessage(role, replacement, toolCalls, toolCallId, toolName, verifiedRead, readReceipts);
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
