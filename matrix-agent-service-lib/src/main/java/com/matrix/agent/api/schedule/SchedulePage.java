package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Bounded plan page; error and empty are distinct. Cursor is opaque to clients. */
public final class SchedulePage implements Parcelable {
    public final int schemaVersion;
    public final int code;
    public final List<ScheduleInfo> items;
    public final String nextCursor;
    public final long sequence;

    public SchedulePage(int code, List<ScheduleInfo> items, String nextCursor, long sequence) {
        this(ParcelSchema.CURRENT, code, items, nextCursor, sequence);
    }

    public SchedulePage(int schemaVersion, int code, List<ScheduleInfo> items, String nextCursor, long sequence) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.nextCursor = nextCursor;
        this.sequence = sequence;
    }

    private SchedulePage(Parcel in) {
        this(in.readInt(),
                in.readInt(),
                in.createTypedArrayList(ScheduleInfo.CREATOR),
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
    public static final Creator<SchedulePage> CREATOR = new Creator<>() {
        @Override public SchedulePage createFromParcel(Parcel in) { return new SchedulePage(in); }
        @Override public SchedulePage[] newArray(int size) { return new SchedulePage[size]; }
    };
}
