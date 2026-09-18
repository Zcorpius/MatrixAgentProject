package com.matrix.agent.contract;

public enum ApiProtocol {
    OPENAI_CHAT,
    ANTHROPIC_MESSAGES,
    GEMINI_GENERATE_CONTENT,
    OLLAMA_CHAT,
    /** 端侧本地模型（MNN-LLM，离线推理，不走 HTTP）。 */
    ON_DEVICE
}
