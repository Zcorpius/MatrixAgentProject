package com.matrix.agent.api.debug;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 调试轨迹事件 Binder 投影（评估 v1.0 §4.3 契约 3）：Host → Launcher 的
 * append-only oneway 事件。内容在 Host 侧已经 {@code DebugTraceRedactor} 净化；
 * 本 DTO 只做传输，不携带任何需要二次脱敏的原始数据。
 */
public final class DebugTraceWireEvent implements Parcelable {

    /**
     * 线阶段名（append-only 冻结值）。单一事实源：Host 的发射端常量与客户端的
     * 时间线编译器都引用此处，防止两端字符串漂移后静默失配。
     */
    public static final String PHASE_ROUND_START = "ROUND_START";
    public static final String PHASE_ROUND_END = "ROUND_END";
    public static final String PHASE_MODEL_REASONING = "MODEL_REASONING";
    public static final String PHASE_MODEL_PROPOSED = "MODEL_PROPOSED";
    public static final String PHASE_POLICY_DECIDED = "POLICY_DECIDED";
    public static final String PHASE_REQUEST_DELIVERED = "REQUEST_DELIVERED";
    public static final String PHASE_DEVICE_VERIFIED = "DEVICE_VERIFIED";

    public final long timestampMs;
    public final String phase;
    public final String taskId;
    public final String traceId;
    /** 单调事件序列；同一长事件的多个 part 共享该值。 */
    public final long eventSequence;
    public final int partIndex;
    public final int partCount;
    public final String payload;
    /** 内嵌面板锚点（Host 解析 ConversationTaskLink 后追加；旧 Host 可为 null）。 */
    public final String conversationId;
    public final String conversationTaskId;
    public final String hostUserMessageId;

    public DebugTraceWireEvent(long timestampMs, String phase, String taskId, String traceId,
            int partIndex, int partCount, String payload) {
        this(timestampMs, phase, taskId, traceId, 0L, partIndex, partCount, payload,
                null, null, null);
    }

    public DebugTraceWireEvent(long timestampMs, String phase, String taskId, String traceId,
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

    private DebugTraceWireEvent(Parcel in) {
        timestampMs = in.readLong();
        phase = in.readString();
        taskId = in.readString();
        traceId = in.readString();
        eventSequence = in.readLong();
        partIndex = in.readInt();
        partCount = in.readInt();
        payload = in.readString();
        conversationId = in.readString();
        conversationTaskId = in.readString();
        hostUserMessageId = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(timestampMs);
        dest.writeString(phase);
        dest.writeString(taskId);
        dest.writeString(traceId);
        dest.writeLong(eventSequence);
        dest.writeInt(partIndex);
        dest.writeInt(partCount);
        dest.writeString(payload);
        dest.writeString(conversationId);
        dest.writeString(conversationTaskId);
        dest.writeString(hostUserMessageId);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<DebugTraceWireEvent> CREATOR = new Creator<>() {
        @Override public DebugTraceWireEvent createFromParcel(Parcel in) {
            return new DebugTraceWireEvent(in);
        }
        @Override public DebugTraceWireEvent[] newArray(int size) {
            return new DebugTraceWireEvent[size];
        }
    };
}
