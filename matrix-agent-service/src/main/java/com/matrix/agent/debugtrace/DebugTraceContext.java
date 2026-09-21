package com.matrix.agent.debugtrace;

/**
 * 模型传输层与 AgentRequest 的窄关联桥。
 *
 * <p>网络模型在 {@code ModelCallExecutor} worker 上同步执行，ThreadLocal 因而只覆盖一次
 * {@code ModelGateway.decide}；它不承载业务数据，也不跨线程传播。没有绑定时，模型配置、
 * 标题生成等非对话调用仍可记录日志，但不会错误进入对话的 debug UI。</p>
 */
public final class DebugTraceContext {
    private static final ThreadLocal<String> RUNTIME_REQUEST_ID = new ThreadLocal<>();

    private DebugTraceContext() { }

    public static Scope bind(String runtimeRequestId) {
        String previous = RUNTIME_REQUEST_ID.get();
        if (runtimeRequestId == null || runtimeRequestId.isBlank()) RUNTIME_REQUEST_ID.remove();
        else RUNTIME_REQUEST_ID.set(runtimeRequestId);
        return () -> {
            if (previous == null) RUNTIME_REQUEST_ID.remove();
            else RUNTIME_REQUEST_ID.set(previous);
        };
    }

    /** null 表示当前模型调用并非某个 AgentRequest 的执行片段。 */
    public static String runtimeRequestIdOrNull() {
        return RUNTIME_REQUEST_ID.get();
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
