package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Bounded execution intent. Capability authorization is intersected with Host policy. */
public final class ScheduleAction implements Parcelable {
    public final int schemaVersion;
    public final int kind;
    public final String text;
    public final String templateId;
    public final int templateVersion;
    public final String parametersJson;
    public final List<String> capabilities;
    public final boolean allowNetwork;
    public final boolean speakResult;

    public ScheduleAction(int kind, String text, String templateId, int templateVersion, String parametersJson, List<String> capabilities, boolean allowNetwork, boolean speakResult) {
        this(ParcelSchema.CURRENT, kind, text, templateId, templateVersion, parametersJson, capabilities, allowNetwork, speakResult);
    }

    public ScheduleAction(int schemaVersion, int kind, String text, String templateId, int templateVersion, String parametersJson, List<String> capabilities, boolean allowNetwork, boolean speakResult) {
        this.schemaVersion = schemaVersion;
        this.kind = kind;
        this.text = text;
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.parametersJson = parametersJson;
        this.capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        this.allowNetwork = allowNetwork;
        this.speakResult = speakResult;
    }

    private ScheduleAction(Parcel in) {
        this(in.readInt(),
                in.readInt(),
                in.readString(),
                in.readString(),
                in.readInt(),
                in.readString(),
                in.createStringArrayList(),
                in.readInt() != 0,
                in.readInt() != 0);
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(kind);
        dest.writeString(text);
        dest.writeString(templateId);
        dest.writeInt(templateVersion);
        dest.writeString(parametersJson);
        dest.writeStringList(capabilities);
        dest.writeInt(allowNetwork ? 1 : 0);
        dest.writeInt(speakResult ? 1 : 0);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleAction> CREATOR = new Creator<>() {
        @Override public ScheduleAction createFromParcel(Parcel in) { return new ScheduleAction(in); }
        @Override public ScheduleAction[] newArray(int size) { return new ScheduleAction[size]; }
    };
}
