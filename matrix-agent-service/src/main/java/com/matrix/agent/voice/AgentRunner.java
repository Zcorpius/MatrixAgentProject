package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import com.matrix.agent.task.AgentOutcome;

/**
 * Agent 执行接口(函数式)。
 *
 * <p>让 {@code VoiceSessionController} 不直接耦合 Repository 类——Controller 测试可用 lambda
 * 注入预设 outcome,无需拉起整个 Engine 栈。生产适配器在 voice 侧把本请求转换成 task 的
 * {@code AgentInvocation}；task 不依赖 voice DTO。生产实现:
 * <pre>{@code
 * AgentRunner runner = req -> repository.execute(adapt(req));
 * }</pre>
 *
 * <p>窄接口(单方法)+ 生产零成本注入(method reference),非伪通用层;仅为 Controller 可测性。
 */
@FunctionalInterface
public interface AgentRunner {
    /**
     * 执行 Agent(阻塞到终态)。
     *
     * @param request 语音请求(含 ASR final 转写、固定主驾身份、默认音区、VOICE 标记与取消令牌)
     * @return Agent 终态结果
     */
    AgentOutcome run(VoiceAgentRequest request);
}
