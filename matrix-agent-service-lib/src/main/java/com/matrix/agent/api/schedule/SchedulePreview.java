package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Validated normalized plan and a bounded next-occurrence preview. It never activates a plan. */
public final class SchedulePreview implements Parcelable {
    public final int schemaVersion;
    public final int code;
    public final ScheduleSpec spec;
    public final List<String> nextOccurrences;
    public final String message;

    public SchedulePreview(int code, ScheduleSpec spec, List<String> nextOccurrences, String message) {
        this(ParcelSchema.CURRENT, code, spec, nextOccurrences, message);
    }

    public SchedulePreview(int schemaVersion, int code, ScheduleSpec spec, List<String> nextOccurrences, String message) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.spec = spec;
        this.nextOccurrences = nextOccurrences == null ? List.of() : List.copyOf(nextOccurrences);
        this.message = message;
    }

    private SchedulePreview(Parcel in) {
        this(in.readInt(),
                in.readInt(),
                in.readTypedObject(ScheduleSpec.CREATOR),
                in.createStringArrayList(),
                in.readString());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeTypedObject(spec, flags);
        dest.writeStringList(nextOccurrences);
        dest.writeString(message);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<SchedulePreview> CREATOR = new Creator<>() {
        @Override public SchedulePreview createFromParcel(Parcel in) { return new SchedulePreview(in); }
        @Override public SchedulePreview[] newArray(int size) { return new SchedulePreview[size]; }
    };
}
