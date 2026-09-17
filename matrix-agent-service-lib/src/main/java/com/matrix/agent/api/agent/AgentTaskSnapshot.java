package com.matrix.agent.api.agent;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 任务安全快照（断线恢复的权威数据源）：≤16 KiB UTF-8。
 * 不含 Trajectory、raw tool/model/provider 数据。
 */
public final class AgentTaskSnapshot implements Parcelable {

    public static final int SAFE_SNAPSHOT_MAX_UTF8 = 16 * 1024;

    public final int schemaVersion;
    public final String taskId;
    public final int state;
    public final long lastSequence;
    public final String safeText;
    public final int errorCode;
    /** 非空表示任务停在 WAITING_CONFIRMATION，等待该 confirmationId 的决策。 */
    public final String pendingConfirmationId;

    public AgentTaskSnapshot(String taskId, int state, long lastSequence,
            String safeText, int errorCode, String pendingConfirmationId) {
        this(ParcelSchema.CURRENT, taskId, state, lastSequence, safeText, errorCode,
                pendingConfirmationId);
    }

    public AgentTaskSnapshot(int schemaVersion, String taskId, int state, long lastSequence,
            String safeText, int errorCode, String pendingConfirmationId) {
        this.schemaVersion = schemaVersion;
        this.taskId = taskId;
        this.state = state;
        this.lastSequence = lastSequence;
        this.safeText = safeText;
        this.errorCode = errorCode;
        this.pendingConfirmationId = pendingConfirmationId;
    }

    private AgentTaskSnapshot(Parcel in) {
        schemaVersion = in.readInt();
        taskId = in.readString();
        state = in.readInt();
        lastSequence = in.readLong();
        safeText = in.readString();
        errorCode = in.readInt();
        pendingConfirmationId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(taskId);
        dest.writeInt(state);
        dest.writeLong(lastSequence);
        dest.writeString(safeText);
        dest.writeInt(errorCode);
        dest.writeString(pendingConfirmationId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AgentTaskSnapshot> CREATOR = new Creator<>() {
        @Override
        public AgentTaskSnapshot createFromParcel(Parcel in) {
            return new AgentTaskSnapshot(in);
        }

        @Override
        public AgentTaskSnapshot[] newArray(int size) {
            return new AgentTaskSnapshot[size];
        }
    };
}
