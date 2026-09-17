package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * {@link VoiceMetricsPort} 的空实现。
 *
 * <p>默认指标实现(不记录任何东西)。真机接 RoomMetrics 等持久化实现时替换。
 * enum singleton,无开销。
 */
public enum NoopVoiceMetrics implements VoiceMetricsPort {
    INSTANCE;

    @Override public void onStateTransition(VoiceSessionState.State from, VoiceSessionState.State to, long durationNanos) { }
    @Override public void onWake(String source) { }
    @Override public void onAgentLatency(long nanos) { }
    @Override public void onError(String code, String stage) { }
    @Override public void onTimeout(String type, String stage) { }
    @Override public void onCancel(String stage) { }
}
