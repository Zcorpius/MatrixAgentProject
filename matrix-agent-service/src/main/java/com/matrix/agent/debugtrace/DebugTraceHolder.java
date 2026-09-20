package com.matrix.agent.debugtrace;

/**
 * 调试轨迹发射器的进程级持有者（评估 v1.0 §4.3 契约 5：Host 唯一 Emitter）。
 *
 * <p>未注册时 {@link #get()} 返回 null——调用方静默跳过（debug 轨迹是旁路观测，
 * 绝不因它缺失而影响主路径）。装配点：AppContainer（带 BuildConfig 门控）。</p>
 */
public final class DebugTraceHolder {

    private static volatile DebugTraceEmitter emitter;

    private DebugTraceHolder() {
    }

    /** 注册唯一实例（AppContainer 装配时调用一次）。 */
    public static void set(DebugTraceEmitter instance) {
        emitter = instance;
    }

    /** 可空：未装配（进程极早期/降级装配）时为 null。 */
    public static DebugTraceEmitter get() {
        return emitter;
    }

    /** 便捷发射：未装配时 no-op（主路径零侵入）。 */
    public static void emit(String phase, String taskId, String payload) {
        DebugTraceEmitter instance = emitter;
        if (instance != null) {
            instance.emit(phase, taskId, payload);
        }
    }

    /** 带宿主绑定的发射：内嵌面板持久化用。 */
    public static void emit(String phase, String taskId, String payload,
            String hostUserMessageId, long generation, long eventSequence) {
        DebugTraceEmitter instance = emitter;
        if (instance != null) {
            instance.emit(phase, taskId, payload, hostUserMessageId, generation,
                    eventSequence);
        }
    }
}
