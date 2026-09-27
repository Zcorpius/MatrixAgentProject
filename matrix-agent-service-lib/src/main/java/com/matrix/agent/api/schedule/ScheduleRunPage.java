package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Bounded occurrence history; error and empty are distinct. */
public final class ScheduleRunPage implements Parcelable {
    public final int schemaVersion;
    public final int code;
    public final List<ScheduleRunInfo> items;
    public final String nextCursor;
    public final long sequence;

    public ScheduleRunPage(int code, List<ScheduleRunInfo> items, String nextCursor, long sequence) {
        this(ParcelSchema.CURRENT, code, items, nextCursor, sequence);
    }

    public ScheduleRunPage(int schemaVersion, int code, List<ScheduleRunInfo> items, String nextCursor, long sequence) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.nextCursor = nextCursor;
        this.sequence = sequence;
    }

    private ScheduleRunPage(Parcel in) {
        this(in.readInt(),
                in.readInt(),
                in.createTypedArrayList(ScheduleRunInfo.CREATOR),
                in.readString(),
                in.readLong());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeTypedList(items);
        dest.writeString(nextCursor);
        dest.writeLong(sequence);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleRunPage> CREATOR = new Creator<>() {
        @Override public ScheduleRunPage createFromParcel(Parcel in) { return new ScheduleRunPage(in); }
        @Override public ScheduleRunPage[] newArray(int size) { return new ScheduleRunPage[size]; }
    };
}
