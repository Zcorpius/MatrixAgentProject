package com.matrix.agent.task;

import com.matrix.agent.identity.AgentRequest;

import com.matrix.agent.contract.ToolCall;

/**
 * Agent Loop 单任务执行预算。不引 tokenizer,字符数用 char-based 估算。
 *
 * <p>五个维度都必须真正生效,不能仅作为字段/getter 存在。
 * <ul>
 *   <li>{@code maxIterations} —— AgentEngine 主循环次数上限(超 → MAX_ITERATIONS);</li>
 *   <li>{@code maxToolCalls} —— 累计 ToolCall 总数上限(超 → MAX_TOOL_CALLS);</li>
 *   <li>{@code totalDeadlineMillis} —— 单一权威 deadline,AgentRuntimeRepository 据此设置
 *       AgentRequest.timeoutMillis(超 → TIMEOUT);</li>
 *   <li>{@code maxMessageChars} —— 单条消息字符上限,所有 system/user/assistant/tool
 *       消息加入 conversation 前如超出此长度会截断(防止超长 system prompt / 用户输入);</li>
 *   <li>{@code totalInputChars} —— conversation 累计字符上限,加入新消息前检查
 *       (超 → BUDGET_EXHAUSTED);</li>
 *   <li>{@code maxMessageCount} —— conversation 消息条数上限(默认 64),防止模型在字符预算内
 *       通过海量短消息无限堆叠(超 → BUDGET_EXHAUSTED)。</li>
 * </ul>
 */
public final class AgentBudget {
    public static final int DEFAULT_MAX_ITERATIONS = 8;
    public static final int DEFAULT_MAX_TOOL_CALLS = 8;
    public static final long DEFAULT_TOTAL_DEADLINE_MILLIS = 60_000L;
    public static final int DEFAULT_MAX_MESSAGE_CHARS = 8_000;
    public static final int DEFAULT_TOTAL_INPUT_CHARS = 32_000;
    public static final int DEFAULT_MAX_MESSAGE_COUNT = 64;
    /**
     * token 维度保守估算系数——1 token ≈ 4 chars(英文)。
     * 中文 token 数会更高(每个汉字 ≈ 1-2 token),用 char/4 是下限,避免误拒。
     * 切 JtokkitTokenizer 后此系数退役。
     */
    public static final int CHAR_PER_TOKEN_FALLBACK = 4;
    /** assistant 单轮 token 上限——切 token 时 enforcement 接入。 */
    public static final int DEFAULT_MAX_ASSISTANT_TOKENS = 1_024;

    private final int maxIterations;
    private final int maxToolCalls;
    private final long totalDeadlineMillis;
    private final int maxMessageChars;
    private final int totalInputChars;
    private final int maxMessageCount;
    /**
     * token 维度字段——此前是 char/4 估算的派生值;
     * 切真实 jtokkit token 时由新构造器显式注入。
     *
     * <p>主路径(appendMessageWithBudget)仍用 char——字段先落地,决策切换留待后续。
     */
    private final int maxMessageTokens;
    private final int totalInputTokens;
    private final int maxAssistantTokens;

    public AgentBudget() {
        this(DEFAULT_MAX_ITERATIONS, DEFAULT_MAX_TOOL_CALLS, DEFAULT_TOTAL_DEADLINE_MILLIS,
                DEFAULT_MAX_MESSAGE_CHARS, DEFAULT_TOTAL_INPUT_CHARS, DEFAULT_MAX_MESSAGE_COUNT);
    }

    public AgentBudget(int maxIterations, int maxToolCalls, long totalDeadlineMillis,
            int maxMessageChars, int totalInputChars) {
        this(maxIterations, maxToolCalls, totalDeadlineMillis, maxMessageChars, totalInputChars,
                DEFAULT_MAX_MESSAGE_COUNT);
    }

    public AgentBudget(int maxIterations, int maxToolCalls, long totalDeadlineMillis,
            int maxMessageChars, int totalInputChars, int maxMessageCount) {
        this(maxIterations, maxToolCalls, totalDeadlineMillis, maxMessageChars, totalInputChars,
                maxMessageCount,
                Math.max(1, maxMessageChars / CHAR_PER_TOKEN_FALLBACK),
                Math.max(1, totalInputChars / CHAR_PER_TOKEN_FALLBACK),
                DEFAULT_MAX_ASSISTANT_TOKENS);
    }

    /**
     * 9 参构造器——显式传 token 维度(切真实 jtokkit token 用)。
     *
     * <p>当前不调用本构造器——主路径仍走 6 参构造器(char/4 估算)。
     * 切换时 AppContainer 用真实 jtokkit 估算 prompt token,通过本构造器注入。
     */
    public AgentBudget(int maxIterations, int maxToolCalls, long totalDeadlineMillis,
            int maxMessageChars, int totalInputChars, int maxMessageCount,
            int maxMessageTokens, int totalInputTokens, int maxAssistantTokens) {
        if (maxIterations <= 0) throw new IllegalArgumentException("maxIterations 必须大于 0");
        if (maxToolCalls <= 0) throw new IllegalArgumentException("maxToolCalls 必须大于 0");
        if (totalDeadlineMillis <= 0) throw new IllegalArgumentException("totalDeadlineMillis 必须大于 0");
        if (maxMessageChars <= 0) throw new IllegalArgumentException("maxMessageChars 必须大于 0");
        if (totalInputChars <= 0) throw new IllegalArgumentException("totalInputChars 必须大于 0");
        if (maxMessageCount <= 0) throw new IllegalArgumentException("maxMessageCount 必须大于 0");
        if (maxMessageTokens <= 0) throw new IllegalArgumentException("maxMessageTokens 必须大于 0");
        if (totalInputTokens <= 0) throw new IllegalArgumentException("totalInputTokens 必须大于 0");
        if (maxAssistantTokens <= 0) throw new IllegalArgumentException("maxAssistantTokens 必须大于 0");
        this.maxIterations = maxIterations;
        this.maxToolCalls = maxToolCalls;
        this.totalDeadlineMillis = totalDeadlineMillis;
        this.maxMessageChars = maxMessageChars;
        this.totalInputChars = totalInputChars;
        this.maxMessageCount = maxMessageCount;
        this.maxMessageTokens = maxMessageTokens;
        this.totalInputTokens = totalInputTokens;
        this.maxAssistantTokens = maxAssistantTokens;
    }

    public int getMaxIterations() { return maxIterations; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public long getTotalDeadlineMillis() { return totalDeadlineMillis; }
    public int getMaxMessageChars() { return maxMessageChars; }
    public int getTotalInputChars() { return totalInputChars; }
    public int getMaxMessageCount() { return maxMessageCount; }

    /**
     * 读字段(而非每次 char/4 估算)——切真实 jtokkit token 时
     * 字段值由 9 参构造器显式注入。
     */
    public int getMaxMessageTokens() {
        return maxMessageTokens;
    }

    public int getTotalInputTokens() {
        return totalInputTokens;
    }

    public int getMaxAssistantTokens() {
        return maxAssistantTokens;
    }
}
