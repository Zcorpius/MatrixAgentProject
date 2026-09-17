package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import com.matrix.agent.task.TaskState;

/**
 * 可播报结果。
 *
 * <p>由 {@link ResponsePresenter} 从 {@link com.matrix.agent.task.AgentOutcome} 生成,
 * 供 TTS 播报。结构上只持有脱敏后的播报文本 {@code text}、语言标签和终态枚举——
 * <b>绝不</b>包含 Trajectory、ToolResult、message、observedState 或任何原始用户数据。
 * 该保证由字段类型本身落实:构造器不接收任何敏感类型。
 *
 * @see ResponsePresenter
 */
public final class SpeakableResponse {
    private final String text;
    private final String languageTag;
    private final TaskState terminalState;

    public SpeakableResponse(String text, String languageTag, TaskState terminalState) {
        if (text == null) throw new IllegalArgumentException("text 不能为空");
        if (languageTag == null) throw new IllegalArgumentException("languageTag 不能为空");
        if (terminalState == null) throw new IllegalArgumentException("terminalState 不能为空");
        this.text = text;
        this.languageTag = languageTag;
        this.terminalState = terminalState;
    }

    /** 播报文本(已脱敏、已截断)。CANCELLED 等场景可为空串,表示静默。 */
    public String getText() {
        return text;
    }

    /** BCP-47 语言标签(Demo 固定 zh-CN),驱动 TTS 引擎选音。 */
    public String getLanguageTag() {
        return languageTag;
    }

    /** 生成本响应所依据的 Agent 终态。仅作元数据,非敏感。 */
    public TaskState getTerminalState() {
        return terminalState;
    }

    /** 播报文本字符数。 */
    public int getCharCount() {
        return text.length();
    }
}
