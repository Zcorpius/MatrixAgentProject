package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 对话控制操作（append/cancel）的稳定返回。 */
public final class ConversationOperationResult implements Parcelable {

    public final int schemaVersion;
    /** MatrixErrorCode 之一。 */
    public final int code;
    public final String clientOperationId;
    public final String conversationId;
    /** 可空：操作针对的消息。 */
    public final String messageId;

    public ConversationOperationResult(int code, String clientOperationId,
            String conversationId, String messageId) {
        this(ParcelSchema.CURRENT, code, clientOperationId, conversationId, messageId);
    }

    public ConversationOperationResult(int schemaVersion, int code, String clientOperationId,
            String conversationId, String messageId) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.clientOperationId = clientOperationId;
        this.conversationId = conversationId;
        this.messageId = messageId;
    }

    private ConversationOperationResult(Parcel in) {
        schemaVersion = in.readInt();
        code = in.readInt();
        clientOperationId = in.readString();
        conversationId = in.readString();
        messageId = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeString(clientOperationId);
        dest.writeString(conversationId);
        dest.writeString(messageId);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationOperationResult> CREATOR = new Creator<>() {
        @Override public ConversationOperationResult createFromParcel(Parcel in) {
            return new ConversationOperationResult(in);
        }
        @Override public ConversationOperationResult[] newArray(int size) {
            return new ConversationOperationResult[size];
        }
    };
}
