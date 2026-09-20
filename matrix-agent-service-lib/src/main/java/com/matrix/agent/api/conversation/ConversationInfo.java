package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 对话线程摘要。owner/zone 由 Host 从调用方身份推导，客户端不可指定。 */
public final class ConversationInfo implements Parcelable {

    /**
     * Frozen title origin（v8 追加）：谁设置了标题。AUTO 只在当前仍为 DEFAULT 时落
     * （比较-and-set），USER 之后永不覆盖；标题来源不表达输入通道。
     */
    public static final int TITLE_ORIGIN_DEFAULT = 0;
    public static final int TITLE_ORIGIN_AUTO = 1;
    public static final int TITLE_ORIGIN_USER = 2;

    public final int schemaVersion;
    public final String conversationId;
    /** 可空：首条消息后由 Host 异步生成。 */
    public final String title;
    public final String ownerUserId;
    public final String vehicleZone;
    public final boolean archived;
    public final long createdAtMs;
    public final long updatedAtMs;

    public ConversationInfo(String conversationId, String title, String ownerUserId,
            String vehicleZone, boolean archived, long createdAtMs, long updatedAtMs) {
        this(ParcelSchema.CURRENT, conversationId, title, ownerUserId, vehicleZone, archived,
                createdAtMs, updatedAtMs);
    }

    public ConversationInfo(int schemaVersion, String conversationId, String title,
            String ownerUserId, String vehicleZone, boolean archived, long createdAtMs,
            long updatedAtMs) {
        this.schemaVersion = schemaVersion;
        this.conversationId = conversationId;
        this.title = title;
        this.ownerUserId = ownerUserId;
        this.vehicleZone = vehicleZone;
        this.archived = archived;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    private ConversationInfo(Parcel in) {
        schemaVersion = in.readInt();
        conversationId = in.readString();
        title = in.readString();
        ownerUserId = in.readString();
        vehicleZone = in.readString();
        archived = in.readByte() != 0;
        createdAtMs = in.readLong();
        updatedAtMs = in.readLong();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(conversationId);
        dest.writeString(title);
        dest.writeString(ownerUserId);
        dest.writeString(vehicleZone);
        dest.writeByte((byte) (archived ? 1 : 0));
        dest.writeLong(createdAtMs);
        dest.writeLong(updatedAtMs);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationInfo> CREATOR = new Creator<>() {
        @Override public ConversationInfo createFromParcel(Parcel in) {
            return new ConversationInfo(in);
        }
        @Override public ConversationInfo[] newArray(int size) {
            return new ConversationInfo[size];
        }
    };
}
