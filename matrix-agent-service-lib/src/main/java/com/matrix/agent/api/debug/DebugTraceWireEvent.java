package com.matrix.agent.api.debug;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Collections;
import java.util.List;

/**
 * 调试轨迹事件 Binder 投影（评估 v1.0 §4.3 契约 3）：Host → Launcher 的
 * append-only oneway 事件。内容在 Host 侧已经 {@code DebugTraceRedactor} 净化；
 * 本 DTO 只做传输，不携带任何需要二次脱敏的原始数据。
 */
public final class DebugTraceWireEvent implements Parcelable {

    public final long timestampMs;
    public final String phase;
    public final String taskId;
    public final String traceId;
    public final int partIndex;
    public final int partCount;
    public final String payload;

    public DebugTraceWireEvent(long timestampMs, String phase, String taskId, String traceId,
            int partIndex, int partCount, String payload) {
        this.timestampMs = timestampMs;
        this.phase = phase;
        this.taskId = taskId;
        this.traceId = traceId;
        this.partIndex = partIndex;
        this.partCount = partCount;
        this.payload = payload == null ? "" : payload;
    }

    private DebugTraceWireEvent(Parcel in) {
        timestampMs = in.readLong();
        phase = in.readString();
        taskId = in.readString();
        traceId = in.readString();
        partIndex = in.readInt();
        partCount = in.readInt();
        payload = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(timestampMs);
        dest.writeString(phase);
        dest.writeString(taskId);
        dest.writeString(traceId);
        dest.writeInt(partIndex);
        dest.writeInt(partCount);
        dest.writeString(payload);
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
