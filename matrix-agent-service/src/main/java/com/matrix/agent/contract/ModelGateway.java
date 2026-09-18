package com.matrix.agent.contract;

import com.matrix.agent.identity.CancellationToken;

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
     * <p>默认实现只包装 call。远端模型把同一个 request token 传给 OkHttp transport，
     * transport 自行把 {@code Call.cancel()} 注册成 abort hook；本地或未来 Provider 若需要
     * 额外资源终止，可覆盖本方法提供自己的 {@link CancellableModelCall}。
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
                // Remote transport owns its token hook; local gateways may override if needed.
            }
        };
    }
}
