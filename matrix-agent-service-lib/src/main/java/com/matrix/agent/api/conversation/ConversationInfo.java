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
    /** TITLE_ORIGIN_*（v3 追加）：AUTO 只在仍为 DEFAULT 时落，USER 之后永不覆盖。 */
    public final int titleOrigin;
    /** 展示元数据（置顶排序），不进模型上下文。 */
    public final boolean pinned;
    /** ConversationMessage.CHANNEL_*（v3 追加）：最近一次用户输入通道。 */
    public final int lastInputChannel;

    public ConversationInfo(String conversationId, String title, String ownerUserId,
            String vehicleZone, boolean archived, long createdAtMs, long updatedAtMs) {
        this(ParcelSchema.CURRENT, conversationId, title, ownerUserId, vehicleZone, archived,
                createdAtMs, updatedAtMs, TITLE_ORIGIN_DEFAULT, false,
                ConversationMessage.CHANNEL_NONE);
    }

    public ConversationInfo(int schemaVersion, String conversationId, String title,
            String ownerUserId, String vehicleZone, boolean archived, long createdAtMs,
            long updatedAtMs) {
        this(schemaVersion, conversationId, title, ownerUserId, vehicleZone, archived,
                createdAtMs, updatedAtMs, TITLE_ORIGIN_DEFAULT, false,
                ConversationMessage.CHANNEL_NONE);
    }

    public ConversationInfo(int schemaVersion, String conversationId, String title,
            String ownerUserId, String vehicleZone, boolean archived, long createdAtMs,
            long updatedAtMs, int titleOrigin, boolean pinned, int lastInputChannel) {
        this.schemaVersion = schemaVersion;
        this.conversationId = conversationId;
        this.title = title;
        this.ownerUserId = ownerUserId;
        this.vehicleZone = vehicleZone;
        this.archived = archived;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
        this.titleOrigin = titleOrigin;
        this.pinned = pinned;
        this.lastInputChannel = lastInputChannel;
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
        // v3 追加字段容错：旧端写入的 parcel 无尾字段，按版本取默认值
        if (schemaVersion >= 3) {
            titleOrigin = in.readInt();
            pinned = in.readByte() != 0;
            lastInputChannel = in.readInt();
        } else {
            titleOrigin = TITLE_ORIGIN_DEFAULT;
            pinned = false;
            lastInputChannel = ConversationMessage.CHANNEL_NONE;
        }
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
        dest.writeInt(titleOrigin);
        dest.writeByte((byte) (pinned ? 1 : 0));
        dest.writeInt(lastInputChannel);
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
