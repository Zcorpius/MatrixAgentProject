package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;

/** Bounded versioned calendar response. Payload follows the registered capability schema. */
public final class ScheduleCalendarResult implements Parcelable {
    public final int schemaVersion, code;
    public final String status, payloadJson, message;
    public ScheduleCalendarResult(int code, String status, String payloadJson, String message) {
        this(ParcelSchema.CURRENT, code, status, payloadJson, message);
    }
    private ScheduleCalendarResult(int version, int code, String status, String payload, String message) {
        schemaVersion = version; this.code = code; this.status = status; payloadJson = payload; this.message = message;
    }
    private ScheduleCalendarResult(Parcel in) { this(in.readInt(), in.readInt(), in.readString(), in.readString(), in.readString()); }
    @Override public void writeToParcel(Parcel out, int flags) { out.writeInt(schemaVersion); out.writeInt(code); out.writeString(status); out.writeString(payloadJson); out.writeString(message); }
    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleCalendarResult> CREATOR = new Creator<>() {
        @Override public ScheduleCalendarResult createFromParcel(Parcel in) { return new ScheduleCalendarResult(in); }
        @Override public ScheduleCalendarResult[] newArray(int size) { return new ScheduleCalendarResult[size]; }
    };
}
