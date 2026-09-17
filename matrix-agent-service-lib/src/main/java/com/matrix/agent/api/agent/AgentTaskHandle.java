package com.matrix.agent.api.agent;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;
import com.matrix.agent.api.common.MatrixErrorCode;

/** submit 的返回句柄；不含 runtime requestId、路径或 secret。 */
public final class AgentTaskHandle implements Parcelable {

    public final int schemaVersion;
    public final String taskId;
    public final long acceptedSequence;
    public final int state;
    /** SUCCESS when accepted; otherwise no task was created and this is a stable rejection code. */
    public final int errorCode;

    public AgentTaskHandle(String taskId, long acceptedSequence, int state) {
        this(ParcelSchema.CURRENT, taskId, acceptedSequence, state, MatrixErrorCode.SUCCESS);
    }

    public AgentTaskHandle(int schemaVersion, String taskId, long acceptedSequence, int state) {
        this(schemaVersion, taskId, acceptedSequence, state, MatrixErrorCode.SUCCESS);
    }

    public AgentTaskHandle(int schemaVersion, String taskId, long acceptedSequence, int state,
            int errorCode) {
        this.schemaVersion = schemaVersion;
        this.taskId = taskId;
        this.acceptedSequence = acceptedSequence;
        this.state = state;
        this.errorCode = errorCode;
    }

    private AgentTaskHandle(Parcel in) {
        schemaVersion = in.readInt();
        taskId = in.readString();
        acceptedSequence = in.readLong();
        state = in.readInt();
        errorCode = schemaVersion >= 2 ? in.readInt() : MatrixErrorCode.SUCCESS;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(taskId);
        dest.writeLong(acceptedSequence);
        dest.writeInt(state);
        dest.writeInt(errorCode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AgentTaskHandle> CREATOR = new Creator<>() {
        @Override
        public AgentTaskHandle createFromParcel(Parcel in) {
            return new AgentTaskHandle(in);
        }

        @Override
        public AgentTaskHandle[] newArray(int size) {
            return new AgentTaskHandle[size];
        }
    };
}
