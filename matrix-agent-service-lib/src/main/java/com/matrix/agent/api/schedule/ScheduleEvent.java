package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Ordered invalidation projection. Clients fetch snapshots; content never rides in event logs. */
public final class ScheduleEvent implements Parcelable {
    public final int schemaVersion;
    public final long sequence;
    public final String scheduleId;
    public final String runId;
    public final String kind;

    public ScheduleEvent(long sequence, String scheduleId, String runId, String kind) {
        this(ParcelSchema.CURRENT, sequence, scheduleId, runId, kind);
    }

    public ScheduleEvent(int schemaVersion, long sequence, String scheduleId, String runId, String kind) {
        this.schemaVersion = schemaVersion;
        this.sequence = sequence;
        this.scheduleId = scheduleId;
        this.runId = runId;
        this.kind = kind;
    }

    private ScheduleEvent(Parcel in) {
        this(in.readInt(),
                in.readLong(),
                in.readString(),
                in.readString(),
                in.readString());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeLong(sequence);
        dest.writeString(scheduleId);
        dest.writeString(runId);
        dest.writeString(kind);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleEvent> CREATOR = new Creator<>() {
        @Override public ScheduleEvent createFromParcel(Parcel in) { return new ScheduleEvent(in); }
        @Override public ScheduleEvent[] newArray(int size) { return new ScheduleEvent[size]; }
    };
}
