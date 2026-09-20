package com.matrix.agent.debugtrace;

import java.util.Collections;
import java.util.List;

/**
 * 调试轨迹事件（评估 v1.0 §4.3 debugTraceUi 契约 2/5/6）：**已净化**的内存态事件。
 *
 * <p>四态显式区分（契约 6）：MODEL_PROPOSED（模型建议）/ MODEL_REASONING（供应商
 * 实际返回的思考，绝不推导）/ POLICY_DECIDED（策略判定）/ REQUEST_DELIVERED
 * （请求已送达）/ DEVICE_VERIFIED（设备已核验）——日志与调试页自身都不传播
 * "请求即事实"的错觉。事件只在内存 ring buffer 中存在：不落库、不进导出、
 * 不进剪贴板/模型上下文/TTS；进程重启即清空。</p>
 */
public final class DebugTraceEvent {

    public static final String PHASE_ROUND_START = "ROUND_START";
    public static final String PHASE_ROUND_END = "ROUND_END";
    public static final String PHASE_MODEL_REASONING = "MODEL_REASONING";
    public static final String PHASE_MODEL_PROPOSED = "MODEL_PROPOSED";
    public static final String PHASE_POLICY_DECIDED = "POLICY_DECIDED";
    public static final String PHASE_REQUEST_DELIVERED = "REQUEST_DELIVERED";
    public static final String PHASE_DEVICE_VERIFIED = "DEVICE_VERIFIED";

    public final long timestampMs;
    public final String phase;
    /** 关联任务 id（runtime_request_id 同族；经 Redactor 截断）。 */
    public final String taskId;
    /** 分片重组键：同一次事件的长内容按 traceId + partIndex/partCount 切分。 */
    public final String traceId;
    public final int partIndex;
    public final int partCount;
    /** 已净化的事件正文（长内容为分片载荷）。 */
    public final String payload;

    public DebugTraceEvent(long timestampMs, String phase, String taskId, String traceId,
            int partIndex, int partCount, String payload) {
        this.timestampMs = timestampMs;
        this.phase = phase;
        this.taskId = taskId;
        this.traceId = traceId;
        this.partIndex = partIndex;
        this.partCount = partCount;
        this.payload = payload == null ? "" : payload;
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
}
