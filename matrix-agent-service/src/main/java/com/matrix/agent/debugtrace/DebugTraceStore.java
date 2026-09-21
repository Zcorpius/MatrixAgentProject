package com.matrix.agent.debugtrace;

import java.util.List;
import java.util.function.Consumer;

/**
 * debug UI 的持久化端口。
 *
 * <p>Emitter 不认识对话表；Store 在 Host 内以 runtimeRequestId 解析 task link，成功后才
 * 向 UI 汇发布带会话锚点的事件。这样日志旁路永远不依赖数据库，而 UI 也不会把非对话任务
 * 的全局事件误挂到任意气泡上。</p>
 */
public interface DebugTraceStore {

    /** 异步持久化并为每个已解析事件调用 {@code delivered}。 */
    void persist(List<DebugTraceEvent> events, Consumer<DebugTraceEvent> delivered);

    /** 已持久化历史，按事件与分片的稳定顺序返回。 */
    List<DebugTraceEvent> history(String hostUserMessageId, int limit);

    /** 量产/降级启动时的防御性清理。 */
    void clearAll();
}
