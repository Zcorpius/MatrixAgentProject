package com.matrix.agent.task.capability;

import com.matrix.agent.demo.MockCapabilityProvider;

/**
 * Capability 验证方式枚举。
 *
 * <p>替换 {@code verificationRequired(boolean)} 二值,区分"不验证"和"按字段回读 /
 * 按 get 读"等不同验证策略,让 {@code MockCapabilityProvider} / 后续版本真实 Provider
 * 按 capability 显式选择验证实现。
 *
 * <ul>
 *   <li>{@link #NONE}——不验证(读操作 / 即时返回的查询)</li>
 *   <li>{@link #READBACK_FIELD}——按 observedState 字段回读(climate / seat / navigation)</li>
 *   <li>{@link #READBACK_GET}——通过另一次 get 调用回读(memory.preference.save → get)</li>
 *   <li>{@link #USER_CONFIRM}——预留,后续版本接 ASR 后实现</li>
 *   <li>{@link #TIMEOUT}——预留,后续版本接 Provider 超时机制后实现</li>
 * </ul>
 *
 * <p>{@code Builder.verificationRequired(true)} 通过 bridge 映射到
 * {@link #READBACK_FIELD}(mock 默认 readback field)。
 */
public enum VerifyMethod {
    NONE,
    READBACK_FIELD,
    READBACK_GET,
    USER_CONFIRM,
    TIMEOUT
}
