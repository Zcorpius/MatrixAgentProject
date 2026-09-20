package com.matrix.agent.task.conversation;

/**
 * 对话 assistant 消息的唯一安全正文来源（设计文档 §9.2）。
 *
 * <p>由 {@link ConversationAssistantProjector} 在 task 可信上下文中构造：
 * {@code MODEL_FINAL} 只承载 StopReason.NO_TOOL_CALL 且非空白的模型终答（经凭据清洗与
 * 长度上限）；其余终态一律 {@code SYNTHESIZED_TERMINAL}——非误导的固定说明文案，
 * 绝不从 trajectory 反解析、不把截断片段伪装成完整回复。{@code displaySafe} 恒为
 * true：本对象的存在即代表“可展示”，未过投影器的模型原始文本不得进入对话表。</p>
 *
 * <p>实现说明：语义上是 record，但刻意写成显式不可变类——lint 的 UAST 分析器在
 * “record + 紧凑构造器 + 方法调用”组合上崩溃（ApiDetector paramList NPE），
 * 显式构造器规避该工具链缺陷而不改变任何契约。</p>
 */
public final class AssistantReply {

    public enum Source { MODEL_FINAL, SYNTHESIZED_TERMINAL }

    private final String text;
    private final Source source;
    private final boolean displaySafe;
    private final boolean truncated;

    public AssistantReply(String text, Source source, boolean displaySafe, boolean truncated) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("AssistantReply.text 必须是非空白文本");
        }
        if (!displaySafe) {
            // 投影器只产出可展示文本；不可展示的内容根本不应构造本对象。
            throw new IllegalArgumentException("AssistantReply 必须是 displaySafe 的");
        }
        this.text = text.strip();
        this.source = source;
        this.displaySafe = true;
        this.truncated = truncated;
    }

    public static AssistantReply modelFinal(String sanitizedText, boolean truncated) {
        return new AssistantReply(sanitizedText, Source.MODEL_FINAL, true, truncated);
    }

    public static AssistantReply synthesized(String explanation) {
        return new AssistantReply(explanation, Source.SYNTHESIZED_TERMINAL, true, false);
    }

    public String text() {
        return text;
    }

    public Source source() {
        return source;
    }

    public boolean displaySafe() {
        return displaySafe;
    }

    public boolean truncated() {
        return truncated;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AssistantReply that)) return false;
        return truncated == that.truncated && displaySafe == that.displaySafe
                && source == that.source && text.equals(that.text);
    }

    @Override public int hashCode() {
        int result = text.hashCode();
        result = 31 * result + source.hashCode();
        result = 31 * result + (displaySafe ? 1 : 0);
        result = 31 * result + (truncated ? 1 : 0);
        return result;
    }

    @Override public String toString() {
        return "AssistantReply{source=" + source + ", truncated=" + truncated
                + ", chars=" + text.length() + "}";
    }
}
