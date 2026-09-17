package com.matrix.agent.api.agent;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 任务事件流单项：≤4 KiB UTF-8；不作为唯一恢复数据源（恢复以 snapshot 为准）。
 * 每任务 sequence 从 1 单调递增。
 */
public final class AgentTaskEvent implements Parcelable {

    public static final int SAFE_PAYLOAD_MAX_UTF8 = 4 * 1024;

    /**
     * Frozen event type assignments. Values are append-only and unknown values must be ignored,
     * allowing a newer Host to stream events to an older launcher safely.
     */
    public static final int TYPE_STATE_CHANGED = 1;
    public static final int TYPE_TEXT_DELTA = 2;
    public static final int TYPE_CONFIRMATION_REQUEST = 3;
    public static final int TYPE_RESYNC_REQUIRED = 4;

    public final int schemaVersion;
    public final String taskId;
    public final long sequence;
    public final long elapsedRealtimeMs;
    public final int type;
    public final int state;
    public final String safePayload;

    public AgentTaskEvent(String taskId, long sequence, long elapsedRealtimeMs,
            int type, int state, String safePayload) {
        this(ParcelSchema.CURRENT, taskId, sequence, elapsedRealtimeMs, type, state, safePayload);
    }

    public AgentTaskEvent(int schemaVersion, String taskId, long sequence, long elapsedRealtimeMs,
            int type, int state, String safePayload) {
        this.schemaVersion = schemaVersion;
        this.taskId = taskId;
        this.sequence = sequence;
        this.elapsedRealtimeMs = elapsedRealtimeMs;
        this.type = type;
        this.state = state;
        this.safePayload = safePayload;
    }

    private AgentTaskEvent(Parcel in) {
        schemaVersion = in.readInt();
        taskId = in.readString();
        sequence = in.readLong();
        elapsedRealtimeMs = in.readLong();
        type = in.readInt();
        state = in.readInt();
        safePayload = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(taskId);
        dest.writeLong(sequence);
        dest.writeLong(elapsedRealtimeMs);
        dest.writeInt(type);
        dest.writeInt(state);
        dest.writeString(safePayload);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AgentTaskEvent> CREATOR = new Creator<>() {
        @Override
        public AgentTaskEvent createFromParcel(Parcel in) {
            return new AgentTaskEvent(in);
        }

        @Override
        public AgentTaskEvent[] newArray(int size) {
            return new AgentTaskEvent[size];
        }
    };
}
