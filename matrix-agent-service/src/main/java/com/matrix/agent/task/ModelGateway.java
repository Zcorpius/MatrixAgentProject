package com.matrix.agent.task;

/**
 * Agent Loop 的模型决策网关。每次调用对应一轮 LLM 推理。
 *
 * 实现方负责:
 * - 把 Provider 无关的 {@link ModelTurnRequest} 转成具体协议(OpenAI/Anthropic/Gemini/Ollama)
 * - 处理 PlannerMode(Native Tool Calling vs Structured JSON Compatibility)
 * - 把模型响应归一化为 {@link ModelTurn}
 *
 * 调用方(Agent Loop)负责:
 * - 拼装 conversation(含上轮 Observation)
 * - 处理 deadline 和 cancel(通过 {@link ModelCallExecutor})
 * - 解析 toolCalls、走 Policy、执行、把 Observation 拼回 conversation
 */
public interface ModelGateway {
    /**
     * Execution resource required by this gateway. Remote providers are network-bound; a native
     * on-device runtime is CPU/native-memory bound and must not starve the shared HTTP/download
     * workers while generating.
     */
    enum ExecutionLane {
        NETWORK,
        SERIAL_LOCAL
    }

    ModelTurn decide(ModelTurnRequest request);

    /** Default preserves the remote-provider execution contract. */
    default ExecutionLane executionLane() {
        return ExecutionLane.NETWORK;
    }

    /**
     * 返回可取消的 ModelCall 包装。
     *
     * <p>默认实现不挂真传输层 abort——call() 直接调 {@link #decide},abort() 仅记录 intent。
     * 真正生效需要实现方覆盖本方法,返回带 abort 能力的 {@link CancellableModelCall}
     * (如 LlmModelGateway 在后续版本接 OkHttp 时挂 Call.cancel)。
     *
     * <p>{@link ModelCallExecutor#decide} 优先调本方法,把 CancellableModelCall.abort 注册到
     * CancellationToken 的 abort hook,让 cancel 触发时立即调用传输层 abort。
     */
    default CancellableModelCall prepare(ModelTurnRequest request) {
        return new CancellableModelCall() {
            @Override
            public ModelTurn call() {
                return decide(request);
            }

            @Override
            public void abort() {
                // 默认无传输层 abort——后续版本接 OkHttp 时由 LlmModelGateway 覆盖
            }
        };
    }
}
