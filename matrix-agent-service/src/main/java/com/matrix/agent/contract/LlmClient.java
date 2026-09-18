package com.matrix.agent.contract;

import com.matrix.agent.identity.CancellationToken;

import java.util.List;

/**
 * LlmPlanner 调用模型 HTTP API 的最小契约。
 *
 * <p>抽出的目的:让 LlmPlanner 在 JVM 单测中可注入 fake(无需起 HttpServer),
 * 验证 memoryRecaller 注入后 userPrompt 含召回 key。生产路径继续用
 * {@link ModelApiClient}(实现本接口,LlmPlanner 旧构造器签名不变)。
 */
public interface LlmClient {
    String complete(ModelConfig config, String systemPrompt, String userPrompt) throws Exception;

    /**
     * cancel + deadline 感知的 complete 重载。
     *
     * <p>生产实现({@link ModelApiClient})把 token + deadline 透传给
     * {@link RetryPolicy#invokeWithRetry(CallableWithRetry, CancellationToken, long)},
     * 让退避期间感知 cancel(立即唤醒 sleep)+ 按剩余 deadline 截断 delay。
     *
     * @param token           取消令牌(null 表示不感知 cancel,与旧重载等价)
     * @param deadlineAtMillis deadline 绝对时间戳(ms);{@link Long#MAX_VALUE} 表示不限制
     */
    String complete(ModelConfig config, String systemPrompt, String userPrompt,
            CancellationToken token, long deadlineAtMillis) throws Exception;

}
