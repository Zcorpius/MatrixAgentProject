package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.common.ParcelSchema;

/**
 * sendText 的稳定返回：被持久化的用户消息投影 + 受理结果码。
 * code != SUCCESS 时消息未落库（恢复未完成 / 校验失败 / 过载），taskId 字段为 null。
 */
public final class ConversationSubmission implements Parcelable {

    public final int schemaVersion;
    public final int code;
    public final String conversationId;
    public final String userMessageId;
    public final String conversationTaskId;
    public final long sequenceNo;
    /** 同 clientOperationId 重放时为 true：返回的是既有消息，任务不会二次执行。 */
    public final boolean replay;

    public ConversationSubmission(int code, String conversationId, String userMessageId,
            String conversationTaskId, long sequenceNo, boolean replay) {
        this(ParcelSchema.CURRENT, code, conversationId, userMessageId, conversationTaskId,
                sequenceNo, replay);
    }

    public ConversationSubmission(int schemaVersion, int code, String conversationId,
            String userMessageId, String conversationTaskId, long sequenceNo, boolean replay) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.conversationId = conversationId;
        this.userMessageId = userMessageId;
        this.conversationTaskId = conversationTaskId;
        this.sequenceNo = sequenceNo;
        this.replay = replay;
    }

    public boolean isAccepted() {
        return code == MatrixErrorCode.SUCCESS;
    }

    private ConversationSubmission(Parcel in) {
        schemaVersion = in.readInt();
        code = in.readInt();
        conversationId = in.readString();
        userMessageId = in.readString();
        conversationTaskId = in.readString();
        sequenceNo = in.readLong();
        replay = in.readByte() != 0;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeString(conversationId);
        dest.writeString(userMessageId);
        dest.writeString(conversationTaskId);
        dest.writeLong(sequenceNo);
        dest.writeByte((byte) (replay ? 1 : 0));
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationSubmission> CREATOR = new Creator<>() {
        @Override public ConversationSubmission createFromParcel(Parcel in) {
            return new ConversationSubmission(in);
        }
        @Override public ConversationSubmission[] newArray(int size) {
            return new ConversationSubmission[size];
        }
    };
}
