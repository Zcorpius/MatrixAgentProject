package com.matrix.agent.ondevice;

/**
 * 端侧推理结束原因。
 *
 * <p>:ondevice 自有枚举，<b>不依赖 {@code core.agent.FinishReason}</b>——由 {@code :app} 的
 * {@code OnDeviceModelGateway} 映射过去。这样 :ondevice 保持对 core.agent 零依赖（架构边界）。
 *
 * <p>注意映射语义：{@link #CANCELLED} 不映射成 {@code FinishReason.NONE}（否则误触 AgentEngine
 * 的 PROTOCOL_ERROR 终止）；取消应走 {@code prepare().abort()} → ModelCallExecutor cancel 机制。
 */
public enum OnDeviceFinishReason {
    /** 正常生成结束（EOS）。 */
    STOP,
    /** 达到 maxNewTokens 截断。 */
    LENGTH,
    /** 被 abort 取消。 */
    CANCELLED,
    /** native 推理失败（nativeError 非空）。 */
    FAILED
}
