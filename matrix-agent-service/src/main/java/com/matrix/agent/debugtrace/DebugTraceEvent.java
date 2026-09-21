package com.matrix.agent.debugtrace;

import java.util.Collections;
import java.util.List;

/**
 * 调试轨迹事件（评估 v1.0 §4.3 debugTraceUi 契约 2/5/6）：**已净化**的内存态事件。
 *
 * <p>四态显式区分（契约 6）：MODEL_PROPOSED（模型建议）/ MODEL_REASONING（供应商
 * 实际返回的思考，绝不推导）/ POLICY_DECIDED（策略判定）/ REQUEST_DELIVERED
 * （请求已送达）/ DEVICE_VERIFIED（设备已核验）——日志与调试页自身都不传播
 * "请求即事实"的错觉。量产构建的事件只在 logcat；internal/debug 构建则会在
 * SQLCipher 内保存已净化投影，以便重新进入同一对话时复现。无论哪种模式，事件均不进入
 * 导出、剪贴板、模型上下文或 TTS。</p>
 */
public final class DebugTraceEvent {

    /** 阶段名的单一事实源是 SDK wire 契约（DebugTraceWireEvent），发射端只做委托。 */
    public static final String PHASE_ROUND_START = com.matrix.agent.api.debug.DebugTraceWireEvent.PHASE_ROUND_START;
    public static final String PHASE_ROUND_END = com.matrix.agent.api.debug.DebugTraceWireEvent.PHASE_ROUND_END;
    public static final String PHASE_MODEL_REASONING = com.matrix.agent.api.debug.DebugTraceWireEvent.PHASE_MODEL_REASONING;
    public static final String PHASE_MODEL_PROPOSED = com.matrix.agent.api.debug.DebugTraceWireEvent.PHASE_MODEL_PROPOSED;
    public static final String PHASE_POLICY_DECIDED = com.matrix.agent.api.debug.DebugTraceWireEvent.PHASE_POLICY_DECIDED;
    public static final String PHASE_REQUEST_DELIVERED = com.matrix.agent.api.debug.DebugTraceWireEvent.PHASE_REQUEST_DELIVERED;
    public static final String PHASE_DEVICE_VERIFIED = com.matrix.agent.api.debug.DebugTraceWireEvent.PHASE_DEVICE_VERIFIED;

    public final long timestampMs;
    public final String phase;
    /** 关联任务 id（runtime_request_id 同族；经 Redactor 截断）。 */
    public final String taskId;
    /** 分片重组键：同一次事件的长内容按 traceId + partIndex/partCount 切分。 */
    public final String traceId;
    /** 全局单调事件序号；同一次长内容的各分片共享该值。 */
    public final long eventSequence;
    public final int partIndex;
    public final int partCount;
    /** 已净化的事件正文（长内容为分片载荷）。 */
    public final String payload;

    /** nullable：仅在对话 task link 成功解析后填充，作为内嵌面板的锚点。 */
    public final String conversationId;
    public final String conversationTaskId;
    public final String hostUserMessageId;

    public DebugTraceEvent(long timestampMs, String phase, String taskId, String traceId,
            int partIndex, int partCount, String payload) {
        this(timestampMs, phase, taskId, traceId, 0L, partIndex, partCount, payload,
                null, null, null);
    }

    public DebugTraceEvent(long timestampMs, String phase, String taskId, String traceId,
            long eventSequence, int partIndex, int partCount, String payload,
            String conversationId, String conversationTaskId, String hostUserMessageId) {
        this.timestampMs = timestampMs;
        this.phase = phase;
        this.taskId = taskId;
        this.traceId = traceId;
        this.eventSequence = eventSequence;
        this.partIndex = partIndex;
        this.partCount = partCount;
        this.payload = payload == null ? "" : payload;
        this.conversationId = conversationId;
        this.conversationTaskId = conversationTaskId;
        this.hostUserMessageId = hostUserMessageId;
    }

    /** 单片便捷构造。 */
    public static DebugTraceEvent single(long timestampMs, String phase, String taskId,
            String payload) {
        return new DebugTraceEvent(timestampMs, phase, taskId, "t" + timestampMs, 0, 1,
                payload);
    }

    /** 兼容多行明细的旧形态（拼为一行，分片由 Emitter 负责）。 */
    public static DebugTraceEvent withDetails(long timestampMs, String phase, String taskId,
            List<String> details) {
        return single(timestampMs, phase, taskId,
                details == null || details.isEmpty() ? ""
                        : String.join(" | ", details));
    }

    public List<String> detailLines() {
        return payload.isEmpty() ? Collections.emptyList() : List.of(payload.split(" \\| "));
    }

    /** 在 runtimeRequestId 成功映射至 ConversationTaskLink 后补齐展示上下文。 */
    public DebugTraceEvent withConversationContext(String conversationId,
            String conversationTaskId, String hostUserMessageId) {
        return new DebugTraceEvent(timestampMs, phase, taskId, traceId, eventSequence,
                partIndex, partCount, payload, conversationId, conversationTaskId,
                hostUserMessageId);
    }
}
