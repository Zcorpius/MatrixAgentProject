package com.matrix.agent.api.handoff;

import android.os.Parcel;
import android.os.Parcelable;

/** Host-authenticated conversation handoff. Deadlines use the device monotonic clock; no content or Intent crosses this boundary. */
public record ExternalAppHandoffRequest(
        int schemaVersion,
        String handoffRequestId,
        String operationId,
        String runtimeRequestId,
        String conversationId,
        String conversationTaskId,
        String hostUserMessageId,
        long hostUserSequence,
        String packageName,
        int reason,
        int preparationMode,
        long createdElapsedRealtimeMs,
        long deadlineElapsedRealtimeMs,
        long operationDeadlineElapsedRealtimeMs) implements Parcelable {
    private ExternalAppHandoffRequest(Parcel source) {
        this(source.readInt(), source.readString(), source.readString(), source.readString(), source.readString(), source.readString(), source.readString(), source.readLong(), source.readString(), source.readInt(), source.readInt(), source.readLong(), source.readLong(), source.readLong());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(handoffRequestId);
        dest.writeString(operationId);
        dest.writeString(runtimeRequestId);
        dest.writeString(conversationId);
        dest.writeString(conversationTaskId);
        dest.writeString(hostUserMessageId);
        dest.writeLong(hostUserSequence);
        dest.writeString(packageName);
        dest.writeInt(reason);
        dest.writeInt(preparationMode);
        dest.writeLong(createdElapsedRealtimeMs);
        dest.writeLong(deadlineElapsedRealtimeMs);
        dest.writeLong(operationDeadlineElapsedRealtimeMs);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<ExternalAppHandoffRequest> CREATOR = new Creator<>() {
        @Override public ExternalAppHandoffRequest createFromParcel(Parcel source) { return new ExternalAppHandoffRequest(source); }
        @Override public ExternalAppHandoffRequest[] newArray(int size) { return new ExternalAppHandoffRequest[size]; }
    };
}
