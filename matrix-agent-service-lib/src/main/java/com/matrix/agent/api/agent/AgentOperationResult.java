package com.matrix.agent.api.agent;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 控制操作（cancel/steer/confirmation/resume）的稳定返回：
 * code/acceptedSequence/taskState，任何情况下不为 void。
 */
public final class AgentOperationResult implements Parcelable {

    public final int schemaVersion;
    /** MatrixErrorCode 之一。 */
    public final int code;
    public final String clientOperationId;
    public final long acceptedSequence;
    /** 操作后任务状态（可能未变化，如 IDEMPOTENCY_CONFLICT 重放）。 */
    public final int taskState;

    public AgentOperationResult(int code, String clientOperationId, long acceptedSequence,
            int taskState) {
        this(ParcelSchema.CURRENT, code, clientOperationId, acceptedSequence, taskState);
    }

    public AgentOperationResult(int schemaVersion, int code, String clientOperationId,
            long acceptedSequence, int taskState) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.clientOperationId = clientOperationId;
        this.acceptedSequence = acceptedSequence;
        this.taskState = taskState;
    }

    private AgentOperationResult(Parcel in) {
        schemaVersion = in.readInt();
        code = in.readInt();
        clientOperationId = in.readString();
        acceptedSequence = in.readLong();
        taskState = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeString(clientOperationId);
        dest.writeLong(acceptedSequence);
        dest.writeInt(taskState);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AgentOperationResult> CREATOR = new Creator<>() {
        @Override
        public AgentOperationResult createFromParcel(Parcel in) {
            return new AgentOperationResult(in);
        }

        @Override
        public AgentOperationResult[] newArray(int size) {
            return new AgentOperationResult[size];
        }
    };
}
