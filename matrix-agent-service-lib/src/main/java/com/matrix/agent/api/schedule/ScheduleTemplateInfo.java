package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Host-reviewed immutable workflow template; clients may choose parameters, not executable nodes. */
public final class ScheduleTemplateInfo implements Parcelable {
    public final int schemaVersion;
    public final String templateId;
    public final int version;
    public final String title;
    public final String description;
    public final List<String> capabilities;
    public final List<ScheduleStepInfo> steps;
    public final String parameterSchema;

    public ScheduleTemplateInfo(String templateId, int version, String title, String description, List<String> capabilities, List<ScheduleStepInfo> steps, String parameterSchema) {
        this(ParcelSchema.CURRENT, templateId, version, title, description, capabilities, steps, parameterSchema);
    }

    public ScheduleTemplateInfo(int schemaVersion, String templateId, int version, String title, String description, List<String> capabilities, List<ScheduleStepInfo> steps, String parameterSchema) {
        this.schemaVersion = schemaVersion;
        this.templateId = templateId;
        this.version = version;
        this.title = title;
        this.description = description;
        this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        this.steps = steps == null ? List.of() : List.copyOf(steps);
        this.parameterSchema = parameterSchema;
    }

    private ScheduleTemplateInfo(Parcel in) {
        this(in.readInt(),
                in.readString(),
                in.readInt(),
                in.readString(),
                in.readString(),
                in.createStringArrayList(),
                in.createTypedArrayList(ScheduleStepInfo.CREATOR),
                in.readString());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(templateId);
        dest.writeInt(version);
        dest.writeString(title);
        dest.writeString(description);
        dest.writeStringList(capabilities);
        dest.writeTypedList(steps);
        dest.writeString(parameterSchema);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleTemplateInfo> CREATOR = new Creator<>() {
        @Override public ScheduleTemplateInfo createFromParcel(Parcel in) { return new ScheduleTemplateInfo(in); }
        @Override public ScheduleTemplateInfo[] newArray(int size) { return new ScheduleTemplateInfo[size]; }
    };
}
