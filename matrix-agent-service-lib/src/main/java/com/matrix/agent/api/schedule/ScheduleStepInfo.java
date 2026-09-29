package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** Safe step projection. Dependencies and template version are frozen with the parent run. */
public final class ScheduleStepInfo implements Parcelable {
    public final int schemaVersion;
    public final String runId;
    public final String stepId;
    public final String title;
    public final List<String> dependencies;
    public final boolean required;
    public final int state;
    public final int attempt;
    public final long startedAt;
    public final long completedAt;
    public final String result;
    public final String reason;
    public final String inputSummary;
    public final long activeMillis;

    public ScheduleStepInfo(String runId, String stepId, String title, List<String> dependencies, boolean required, int state, int attempt, long startedAt, long completedAt, String result, String reason) {
        this(ParcelSchema.CURRENT, runId, stepId, title, dependencies, required, state, attempt, startedAt, completedAt, result, reason);
    }

    public ScheduleStepInfo(int schemaVersion, String runId, String stepId, String title, List<String> dependencies, boolean required, int state, int attempt, long startedAt, long completedAt, String result, String reason) {
        this(schemaVersion, runId, stepId, title, dependencies, required, state, attempt, startedAt, completedAt, result, reason, "", 0);
    }
    public ScheduleStepInfo(int schemaVersion, String runId, String stepId, String title, List<String> dependencies,
            boolean required, int state, int attempt, long startedAt, long completedAt, String result, String reason,
            String inputSummary, long activeMillis) {
        this.inputSummary = inputSummary; this.activeMillis = activeMillis;
        this.schemaVersion = schemaVersion;
        this.runId = runId;
        this.stepId = stepId;
        this.title = title;
        this.dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        this.required = required;
        this.state = state;
        this.attempt = attempt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.result = result;
        this.reason = reason;
    }

    private ScheduleStepInfo(Parcel in) {
        schemaVersion = in.readInt(); runId = in.readString(); stepId = in.readString(); title = in.readString();
        var values = in.createStringArrayList(); dependencies = values == null ? List.of() : List.copyOf(values);
        required = in.readInt() != 0; state = in.readInt(); attempt = in.readInt();
        startedAt = in.readLong(); completedAt = in.readLong(); result = in.readString(); reason = in.readString();
        inputSummary = schemaVersion >= 12 ? in.readString() : "";
        activeMillis = schemaVersion >= 12 ? in.readLong() : 0;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(runId);
        dest.writeString(stepId);
        dest.writeString(title);
        dest.writeStringList(dependencies);
        dest.writeInt(required ? 1 : 0);
        dest.writeInt(state);
        dest.writeInt(attempt);
        dest.writeLong(startedAt);
        dest.writeLong(completedAt);
        dest.writeString(result);
        dest.writeString(reason);
        if (schemaVersion >= 12) { dest.writeString(inputSummary); dest.writeLong(activeMillis); }
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleStepInfo> CREATOR = new Creator<>() {
        @Override public ScheduleStepInfo createFromParcel(Parcel in) { return new ScheduleStepInfo(in); }
        @Override public ScheduleStepInfo[] newArray(int size) { return new ScheduleStepInfo[size]; }
    };
}
