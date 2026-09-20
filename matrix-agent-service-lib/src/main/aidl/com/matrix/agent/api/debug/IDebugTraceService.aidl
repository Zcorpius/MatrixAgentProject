package com.matrix.agent.api.debug;

import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.api.debug.IDebugTraceCallback;

/**
 * 调试轨迹订阅（评估 v1.0 §4.3 契约 3）：独立于 IConversationCallback 的
 * append-only 通道。Host 侧 BuildConfig.MATRIX_DEBUG_TRACE_UI=false 时拒绝注册
 * 且不产生事件。注册即回放有界 ring buffer 快照，随后实时推送。
 */
interface IDebugTraceService {
    /** 订阅；返回回放快照（可能为空）。UI 关闭或不可用时返回空列表。 */
    List<DebugTraceWireEvent> subscribe(IDebugTraceCallback callback);

    void unsubscribe(IDebugTraceCallback callback);

    /** 按宿主用户消息 + 任务分页读取已持久化的调试轨迹（升序；契约 3）。 */
    List<DebugTraceWireEvent> loadHistory(String hostUserMessageId,
            String conversationTaskId, int limit);
}
