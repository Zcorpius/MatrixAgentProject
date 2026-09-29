package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Dynamic readiness, independent of advertised static feature support. */
public final class ScheduleReadiness implements Parcelable {
    public final int schemaVersion;
    public final int code;
    public final boolean exactAlarmAllowed;
    public final boolean notificationsAllowed;
    public final boolean userReady;
    public final boolean calendarReadable;
    public final boolean calendarWritable;
    public final String reason;

    public ScheduleReadiness(int code, boolean exactAlarmAllowed, boolean notificationsAllowed, boolean userReady, boolean calendarReadable, boolean calendarWritable, String reason) {
        this(ParcelSchema.CURRENT, code, exactAlarmAllowed, notificationsAllowed, userReady, calendarReadable, calendarWritable, reason);
    }

    public ScheduleReadiness(int schemaVersion, int code, boolean exactAlarmAllowed, boolean notificationsAllowed, boolean userReady, boolean calendarReadable, boolean calendarWritable, String reason) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.exactAlarmAllowed = exactAlarmAllowed;
        this.notificationsAllowed = notificationsAllowed;
        this.userReady = userReady;
        this.calendarReadable = calendarReadable;
        this.calendarWritable = calendarWritable;
        this.reason = reason;
    }

    private ScheduleReadiness(Parcel in) {
        this(in.readInt(),
                in.readInt(),
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readInt() != 0,
                in.readString());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeInt(exactAlarmAllowed ? 1 : 0);
        dest.writeInt(notificationsAllowed ? 1 : 0);
        dest.writeInt(userReady ? 1 : 0);
        dest.writeInt(calendarReadable ? 1 : 0);
        dest.writeInt(calendarWritable ? 1 : 0);
        dest.writeString(reason);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleReadiness> CREATOR = new Creator<>() {
        @Override public ScheduleReadiness createFromParcel(Parcel in) { return new ScheduleReadiness(in); }
        @Override public ScheduleReadiness[] newArray(int size) { return new ScheduleReadiness[size]; }
    };
}
