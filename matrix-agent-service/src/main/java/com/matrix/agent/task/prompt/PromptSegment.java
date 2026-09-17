package com.matrix.agent.task.prompt;

/**
 * Prompt 段落不可变单元——PromptBuilder 装配的最小粒度。
 *
 * <p>{@link Type} 描述段的语义角色,上下文压缩 / zone-aware ToolSchemaView
 * 会按 Type 选择压缩 / 截断策略(BASE / ZONE_HINT 不动,RECALLED_MEMORY 可截断)。
 */
public final class PromptSegment {
    public enum Type {
        /** 系统角色 + 任务约束(旧版基础文案),不可压缩。 */
        BASE,
        /** 已召回的 Memory key 列表,可按 token 预算截断。 */
        RECALLED_MEMORY,
        /** Capability / Tool schema 摘要,字段级 ToolSchemaView 替换 capability 级。 */
        TOOL_LIST,
        /** per-zone 提示(主驾/副驾权限差异),不可压缩。 */
        ZONE_HINT;
    }

    private final Type type;
    private final String text;

    public PromptSegment(Type type, String text) {
        if (type == null) throw new IllegalArgumentException("type 不能为空");
        if (text == null) throw new IllegalArgumentException("text 不能为空");
        this.type = type;
        this.text = text;
    }

    public Type getType() { return type; }
    public String getText() { return text; }

    /** 段落字符估算——用 char,切 token 后改为 tokenEstimate。 */
    public int estimateChars() { return text.length(); }

    @Override
    public String toString() {
        return "PromptSegment{type=" + type + ", chars=" + estimateChars() + "}";
    }
}
