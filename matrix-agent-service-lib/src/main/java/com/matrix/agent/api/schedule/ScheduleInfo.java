package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Plan projection. ARMED describes coverage by the global alarm, not one OS alarm per plan. */
public final class ScheduleInfo implements Parcelable {
    public final int schemaVersion;
    public final String scheduleId;
    public final long revision;
    public final int state;
    public final int health;
    public final String reason;
    public final long nextDueAt;
    public final String actor;
    public final String zone;
    public final ScheduleSpec spec;
    public final long lastSequence;

    public ScheduleInfo(String scheduleId, long revision, int state, int health, String reason, long nextDueAt, String actor, String zone, ScheduleSpec spec, long lastSequence) {
        this(ParcelSchema.CURRENT, scheduleId, revision, state, health, reason, nextDueAt, actor, zone, spec, lastSequence);
    }

    public ScheduleInfo(int schemaVersion, String scheduleId, long revision, int state, int health, String reason, long nextDueAt, String actor, String zone, ScheduleSpec spec, long lastSequence) {
        this.schemaVersion = schemaVersion;
        this.scheduleId = scheduleId;
        this.revision = revision;
        this.state = state;
        this.health = health;
        this.reason = reason;
        this.nextDueAt = nextDueAt;
        this.actor = actor;
        this.zone = zone;
        this.spec = spec;
        this.lastSequence = lastSequence;
    }

    private ScheduleInfo(Parcel in) {
        this(in.readInt(),
                in.readString(),
                in.readLong(),
                in.readInt(),
                in.readInt(),
                in.readString(),
                in.readLong(),
                in.readString(),
                in.readString(),
                in.readTypedObject(ScheduleSpec.CREATOR),
                in.readLong());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(scheduleId);
        dest.writeLong(revision);
        dest.writeInt(state);
        dest.writeInt(health);
        dest.writeString(reason);
        dest.writeLong(nextDueAt);
        dest.writeString(actor);
        dest.writeString(zone);
        dest.writeTypedObject(spec, flags);
        dest.writeLong(lastSequence);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleInfo> CREATOR = new Creator<>() {
        @Override public ScheduleInfo createFromParcel(Parcel in) { return new ScheduleInfo(in); }
        @Override public ScheduleInfo[] newArray(int size) { return new ScheduleInfo[size]; }
    };
}
