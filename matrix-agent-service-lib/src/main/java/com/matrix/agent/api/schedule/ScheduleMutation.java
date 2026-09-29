package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Stable mutation receipt. Replaying an operation returns its original receipt. */
public final class ScheduleMutation implements Parcelable {
    public final int schemaVersion;
    public final int code;
    public final String operationId;
    public final String scheduleId;
    public final String runId;
    public final long revision;
    public final long sequence;
    public final String message;

    public ScheduleMutation(int code, String operationId, String scheduleId, String runId, long revision, long sequence, String message) {
        this(ParcelSchema.CURRENT, code, operationId, scheduleId, runId, revision, sequence, message);
    }

    public ScheduleMutation(int schemaVersion, int code, String operationId, String scheduleId, String runId, long revision, long sequence, String message) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.operationId = operationId;
        this.scheduleId = scheduleId;
        this.runId = runId;
        this.revision = revision;
        this.sequence = sequence;
        this.message = message;
    }

    private ScheduleMutation(Parcel in) {
        this(in.readInt(),
                in.readInt(),
                in.readString(),
                in.readString(),
                in.readString(),
                in.readLong(),
                in.readLong(),
                in.readString());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeString(operationId);
        dest.writeString(scheduleId);
        dest.writeString(runId);
        dest.writeLong(revision);
        dest.writeLong(sequence);
        dest.writeString(message);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleMutation> CREATOR = new Creator<>() {
        @Override public ScheduleMutation createFromParcel(Parcel in) { return new ScheduleMutation(in); }
        @Override public ScheduleMutation[] newArray(int size) { return new ScheduleMutation[size]; }
    };
}
