package com.matrix.agent.task;

/** LLM 单轮 finish_reason 的归一化表示,Provider 无关。 */
public enum FinishReason {
    /** 模型未返回可识别的 finish_reason。 */
    NONE,
    /** 模型选择直接答复(STOP),本轮不调用 tool。 */
    STOP,
    /** 模型因 max_tokens 截断(LENGTH),Observation 可能不完整。 */
    LENGTH,
    /** 模型请求调用 tool(TOOL_CALLS)。 */
    TOOL_CALLS
}
