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

    public SendTextRequest(String conversationId, String text, String languageTag) {
        this(ParcelSchema.CURRENT, conversationId, text, languageTag);
    }

    public SendTextRequest(int schemaVersion, String conversationId, String text,
            String languageTag) {
        this.schemaVersion = schemaVersion;
        this.conversationId = conversationId;
        this.text = text;
        this.languageTag = languageTag;
    }

    private SendTextRequest(Parcel in) {
        schemaVersion = in.readInt();
        conversationId = in.readString();
        text = in.readString();
        languageTag = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(conversationId);
        dest.writeString(text);
        dest.writeString(languageTag);
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
