package com.matrix.agent.api.schedule;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;
import java.util.List;

/** One occurrence and its delivery facts; zero timestamps mean not observed, never fabricated. */
public final class ScheduleRunInfo implements Parcelable {
    public final int schemaVersion;
    public final String runId;
    public final String scheduleId;
    public final String title;
    public final String occurrenceKey;
    public final int state;
    public final int deliveryStatus;
    public final long scheduledAt;
    public final long receivedAt;
    public final long admittedAt;
    public final long startedAt;
    public final long completedAt;
    public final long deliveredAt;
    public final String result;
    public final String reason;
    public final String runtimeRequestId;
    public final long lastSequence;
    public final String deliveryFactsJson;
    public final String templateId;
    public final int templateVersion;

    public ScheduleRunInfo(String runId, String scheduleId, String title, String occurrenceKey, int state, int deliveryStatus, long scheduledAt, long receivedAt, long admittedAt, long startedAt, long completedAt, long deliveredAt, String result, String reason, String runtimeRequestId, long lastSequence) {
        this(ParcelSchema.CURRENT, runId, scheduleId, title, occurrenceKey, state, deliveryStatus, scheduledAt, receivedAt, admittedAt, startedAt, completedAt, deliveredAt, result, reason, runtimeRequestId, lastSequence);
    }

    public ScheduleRunInfo(int schemaVersion, String runId, String scheduleId, String title, String occurrenceKey, int state, int deliveryStatus, long scheduledAt, long receivedAt, long admittedAt, long startedAt, long completedAt, long deliveredAt, String result, String reason, String runtimeRequestId, long lastSequence) {
        this(schemaVersion, runId, scheduleId, title, occurrenceKey, state, deliveryStatus, scheduledAt,
                receivedAt, admittedAt, startedAt, completedAt, deliveredAt, result, reason, runtimeRequestId, lastSequence, "{}");
    }
    public ScheduleRunInfo(int schemaVersion, String runId, String scheduleId, String title, String occurrenceKey,
            int state, int deliveryStatus, long scheduledAt, long receivedAt, long admittedAt, long startedAt,
            long completedAt, long deliveredAt, String result, String reason, String runtimeRequestId, long lastSequence,
            String deliveryFactsJson) {
        this(schemaVersion, runId, scheduleId, title, occurrenceKey, state, deliveryStatus, scheduledAt,
                receivedAt, admittedAt, startedAt, completedAt, deliveredAt, result, reason, runtimeRequestId,
                lastSequence, deliveryFactsJson, "", 0);
    }
    public ScheduleRunInfo(int schemaVersion, String runId, String scheduleId, String title, String occurrenceKey,
            int state, int deliveryStatus, long scheduledAt, long receivedAt, long admittedAt, long startedAt,
            long completedAt, long deliveredAt, String result, String reason, String runtimeRequestId, long lastSequence,
            String deliveryFactsJson, String templateId, int templateVersion) {
        this.templateId = templateId; this.templateVersion = templateVersion;
        this.deliveryFactsJson = deliveryFactsJson;
        this.schemaVersion = schemaVersion;
        this.runId = runId;
        this.scheduleId = scheduleId;
        this.title = title;
        this.occurrenceKey = occurrenceKey;
        this.state = state;
        this.deliveryStatus = deliveryStatus;
        this.scheduledAt = scheduledAt;
        this.receivedAt = receivedAt;
        this.admittedAt = admittedAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.deliveredAt = deliveredAt;
        this.result = result;
        this.reason = reason;
        this.runtimeRequestId = runtimeRequestId;
        this.lastSequence = lastSequence;
    }

    private ScheduleRunInfo(Parcel in) {
        schemaVersion = in.readInt();
        runId = in.readString(); scheduleId = in.readString(); title = in.readString(); occurrenceKey = in.readString();
        state = in.readInt(); deliveryStatus = in.readInt(); scheduledAt = in.readLong(); receivedAt = in.readLong();
        admittedAt = in.readLong(); startedAt = in.readLong(); completedAt = in.readLong(); deliveredAt = in.readLong();
        result = in.readString(); reason = in.readString(); runtimeRequestId = in.readString(); lastSequence = in.readLong();
        deliveryFactsJson = schemaVersion >= 11 ? in.readString() : "{}";
        templateId = schemaVersion >= 12 ? in.readString() : "";
        templateVersion = schemaVersion >= 12 ? in.readInt() : 0;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(runId);
        dest.writeString(scheduleId);
        dest.writeString(title);
        dest.writeString(occurrenceKey);
        dest.writeInt(state);
        dest.writeInt(deliveryStatus);
        dest.writeLong(scheduledAt);
        dest.writeLong(receivedAt);
        dest.writeLong(admittedAt);
        dest.writeLong(startedAt);
        dest.writeLong(completedAt);
        dest.writeLong(deliveredAt);
        dest.writeString(result);
        dest.writeString(reason);
        dest.writeString(runtimeRequestId);
        dest.writeLong(lastSequence);
        if (schemaVersion >= 11) dest.writeString(deliveryFactsJson);
        if (schemaVersion >= 12) { dest.writeString(templateId); dest.writeInt(templateVersion); }
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<ScheduleRunInfo> CREATOR = new Creator<>() {
        @Override public ScheduleRunInfo createFromParcel(Parcel in) { return new ScheduleRunInfo(in); }
        @Override public ScheduleRunInfo[] newArray(int size) { return new ScheduleRunInfo[size]; }
    };
}
