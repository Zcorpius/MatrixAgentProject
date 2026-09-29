package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** A plan edit contains intent only; owner, actor, epoch and acceptance identifiers are Host facts. */
public final class ScheduleSpec implements Parcelable {
    public final int schemaVersion;
    public final String title;
    public final ScheduleTiming timing;
    public final ScheduleAction action;
    public final long graceMillis;
    public final int misfirePolicy;

    public ScheduleSpec(String title, ScheduleTiming timing, ScheduleAction action, long graceMillis, int misfirePolicy) {
        this(ParcelSchema.CURRENT, title, timing, action, graceMillis, misfirePolicy);
    }

    public ScheduleSpec(int schemaVersion, String title, ScheduleTiming timing, ScheduleAction action, long graceMillis, int misfirePolicy) {
        this.schemaVersion = schemaVersion;
        this.title = title;
        this.timing = timing;
        this.action = action;
        this.graceMillis = graceMillis;
        this.misfirePolicy = misfirePolicy;
    }

    private ScheduleSpec(Parcel in) {
        this(in.readInt(),
                in.readString(),
                in.readTypedObject(ScheduleTiming.CREATOR),
                in.readTypedObject(ScheduleAction.CREATOR),
                in.readLong(),
                in.readInt());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(title);
        dest.writeTypedObject(timing, flags);
        dest.writeTypedObject(action, flags);
        dest.writeLong(graceMillis);
        dest.writeInt(misfirePolicy);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleSpec> CREATOR = new Creator<>() {
        @Override public ScheduleSpec createFromParcel(Parcel in) { return new ScheduleSpec(in); }
        @Override public ScheduleSpec[] newArray(int size) { return new ScheduleSpec[size]; }
    };
}
