package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;

/** Temporary assistant body snapshot. Final ConversationMessage remains the only durable answer. */
public final class ConversationAssistantStream implements Parcelable {
    public final int schemaVersion;
    public final String conversationId, conversationTaskId, runtimeRequestId;
    public final int turn;
    public final long sequence;
    public final String text;
    public final boolean cleared;
    public ConversationAssistantStream(String conversationId, String taskId, String requestId,
            int turn, long sequence, String text, boolean cleared) {
        this.schemaVersion = ParcelSchema.CURRENT;
        this.conversationId = conversationId; conversationTaskId = taskId; runtimeRequestId = requestId;
        this.turn = turn; this.sequence = sequence; this.text = text; this.cleared = cleared;
    }
    private ConversationAssistantStream(Parcel in) {
        schemaVersion = in.readInt(); conversationId = in.readString(); conversationTaskId = in.readString();
        runtimeRequestId = in.readString(); turn = in.readInt(); sequence = in.readLong(); text = in.readString();
        cleared = in.readInt() != 0;
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(schemaVersion); out.writeString(conversationId); out.writeString(conversationTaskId);
        out.writeString(runtimeRequestId); out.writeInt(turn); out.writeLong(sequence); out.writeString(text);
        out.writeInt(cleared ? 1 : 0);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<ConversationAssistantStream> CREATOR = new Creator<>() {
        @Override public ConversationAssistantStream createFromParcel(Parcel in) { return new ConversationAssistantStream(in); }
        @Override public ConversationAssistantStream[] newArray(int size) { return new ConversationAssistantStream[size]; }
    };
}
