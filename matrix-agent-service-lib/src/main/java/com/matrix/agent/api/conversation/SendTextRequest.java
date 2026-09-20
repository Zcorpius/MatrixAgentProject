package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 文字消息提交。text 长度约束与 AgentRequest 一致（1..4096 UTF-16 chars，≤16KB UTF-8），
 * Host 侧再校验，不信任客户端。
 */
public final class SendTextRequest implements Parcelable {

    public final int schemaVersion;
    public final String conversationId;
    public final String text;
    /** 可空：跟随系统。 */
    public final String languageTag;
    /** 可空（v4 追加）：引用回复的目标消息；Host 校验同会话后落引用快照。 */
    public final String quotedMessageId;

    public SendTextRequest(String conversationId, String text, String languageTag) {
        this(ParcelSchema.CURRENT, conversationId, text, languageTag, null);
    }

    public SendTextRequest(String conversationId, String text, String languageTag,
            String quotedMessageId) {
        this(ParcelSchema.CURRENT, conversationId, text, languageTag, quotedMessageId);
    }

    public SendTextRequest(int schemaVersion, String conversationId, String text,
            String languageTag) {
        this(schemaVersion, conversationId, text, languageTag, null);
    }

    public SendTextRequest(int schemaVersion, String conversationId, String text,
            String languageTag, String quotedMessageId) {
        this.schemaVersion = schemaVersion;
        this.conversationId = conversationId;
        this.text = text;
        this.languageTag = languageTag;
        this.quotedMessageId = quotedMessageId;
    }

    private SendTextRequest(Parcel in) {
        schemaVersion = in.readInt();
        conversationId = in.readString();
        text = in.readString();
        languageTag = in.readString();
        // v4 追加字段容错：旧端 parcel 无尾字段取 null
        quotedMessageId = schemaVersion >= 4 ? in.readString() : null;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(conversationId);
        dest.writeString(text);
        dest.writeString(languageTag);
        dest.writeString(quotedMessageId);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<SendTextRequest> CREATOR = new Creator<>() {
        @Override public SendTextRequest createFromParcel(Parcel in) {
            return new SendTextRequest(in);
        }
        @Override public SendTextRequest[] newArray(int size) {
            return new SendTextRequest[size];
        }
    };
}
