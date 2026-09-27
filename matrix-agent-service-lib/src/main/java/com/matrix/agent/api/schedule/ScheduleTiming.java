package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Normalized requested time. Weekday mask uses Monday at bit 0; dates are ISO-8601. */
public final class ScheduleTiming implements Parcelable {
    public final int schemaVersion;
    public final int kind;
    public final String zoneId;
    public final long atMillis;
    public final long delayMillis;
    public final String localTime;
    public final int weekdaysMask;
    public final String startDate;
    public final String endDate;
    public final boolean followDeviceZone;
    public final String calendarBindingId;
    public final long calendarOffsetMillis;

    public ScheduleTiming(int kind, String zoneId, long atMillis, long delayMillis, String localTime, int weekdaysMask, String startDate, String endDate, boolean followDeviceZone, String calendarBindingId, long calendarOffsetMillis) {
        this(ParcelSchema.CURRENT, kind, zoneId, atMillis, delayMillis, localTime, weekdaysMask, startDate, endDate, followDeviceZone, calendarBindingId, calendarOffsetMillis);
    }

    public ScheduleTiming(int schemaVersion, int kind, String zoneId, long atMillis, long delayMillis, String localTime, int weekdaysMask, String startDate, String endDate, boolean followDeviceZone, String calendarBindingId, long calendarOffsetMillis) {
        this.schemaVersion = schemaVersion;
        this.kind = kind;
        this.zoneId = zoneId;
        this.atMillis = atMillis;
        this.delayMillis = delayMillis;
        this.localTime = localTime;
        this.weekdaysMask = weekdaysMask;
        this.startDate = startDate;
        this.endDate = endDate;
        this.followDeviceZone = followDeviceZone;
        this.calendarBindingId = calendarBindingId;
        this.calendarOffsetMillis = calendarOffsetMillis;
    }

    private ScheduleTiming(Parcel in) {
        this(in.readInt(),
                in.readInt(),
                in.readString(),
                in.readLong(),
                in.readLong(),
                in.readString(),
                in.readInt(),
                in.readString(),
                in.readString(),
                in.readInt() != 0,
                in.readString(),
                in.readLong());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(kind);
        dest.writeString(zoneId);
        dest.writeLong(atMillis);
        dest.writeLong(delayMillis);
        dest.writeString(localTime);
        dest.writeInt(weekdaysMask);
        dest.writeString(startDate);
        dest.writeString(endDate);
        dest.writeInt(followDeviceZone ? 1 : 0);
        dest.writeString(calendarBindingId);
        dest.writeLong(calendarOffsetMillis);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleTiming> CREATOR = new Creator<>() {
        @Override public ScheduleTiming createFromParcel(Parcel in) { return new ScheduleTiming(in); }
        @Override public ScheduleTiming[] newArray(int size) { return new ScheduleTiming[size]; }
    };
}
