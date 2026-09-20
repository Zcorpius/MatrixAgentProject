package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 单条对话消息的稳定投影。text 永远是“已定稿”内容：partial 转写只经
 * IConversationCallback.onTransientTranscript 内存下发，不进入本 DTO。
 */
public final class ConversationMessage implements Parcelable {

    /** Frozen role assignments（只追加）。 */
    public static final int ROLE_USER = 0;
    public static final int ROLE_ASSISTANT = 1;
    public static final int ROLE_SYSTEM = 2;

    /** Frozen status assignments，与 Host PersistedMessageStatus 一一对应（只追加）。 */
    public static final int STATUS_ACCEPTED = 0;
    public static final int STATUS_RUNNING = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_FAILED = 3;
    public static final int STATUS_CANCELLED = 4;
    /** 写操作可能已发出但无法确认；UI 必须忠实展示“未知”，不得谎报取消或失败。 */
    public static final int STATUS_EXECUTION_UNKNOWN = 5;
    public static final int STATUS_REJECTED = 6;

    /** Frozen channel assignments；仅 USER 消息携带，其他角色为 CHANNEL_NONE。 */
    public static final int CHANNEL_NONE = 0;
    public static final int CHANNEL_TEXT = 1;
    public static final int CHANNEL_PTT = 2;
    public static final int CHANNEL_WAKE = 3;

    /**
     * Frozen input kind（v8 追加）：主提交触发任务；STEER 是并入运行中宿主任务的
     * 附属输入——无独立任务链接、无能力轨迹、不触发标题生成，状态随宿主镜像收敛。
     */
    public static final int INPUT_PRIMARY = 0;
    public static final int INPUT_STEER = 1;

    /**
     * Frozen steer delivery state（v8 追加）。展示规则：“已并入”只由 OFFERED 声称；
     * 恢复后仍 PENDING 的记录必须显示“未确认是否并入”，FAILED 显示“未能并入”。
     */
    public static final int STEER_DELIVERY_PENDING = 0;
    public static final int STEER_DELIVERY_OFFERED = 1;
    public static final int STEER_DELIVERY_FAILED = 2;

    public final int schemaVersion;
    public final String conversationId;
    public final String messageId;
    public final long sequenceNo;
    public final int role;
    public final int status;
    public final int channel;
    public final String text;
    /** 可空。 */
    public final String languageTag;
    /** 可空；存在时关联本条 USER 消息触发的对话任务。 */
    public final String conversationTaskId;
    /** SUCCESS(0) 之外的稳定错误码；无错误为 0。 */
    public final int failureCode;
    public final long createdAtMs;
    public final long updatedAtMs;

    public ConversationMessage(String conversationId, String messageId, long sequenceNo,
            int role, int status, int channel, String text, String languageTag,
            String conversationTaskId, int failureCode, long createdAtMs, long updatedAtMs) {
        this(ParcelSchema.CURRENT, conversationId, messageId, sequenceNo, role, status, channel,
                text, languageTag, conversationTaskId, failureCode, createdAtMs, updatedAtMs);
    }

    public ConversationMessage(int schemaVersion, String conversationId, String messageId,
            long sequenceNo, int role, int status, int channel, String text, String languageTag,
            String conversationTaskId, int failureCode, long createdAtMs, long updatedAtMs) {
        this.schemaVersion = schemaVersion;
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.sequenceNo = sequenceNo;
        this.role = role;
        this.status = status;
        this.channel = channel;
        this.text = text;
        this.languageTag = languageTag;
        this.conversationTaskId = conversationTaskId;
        this.failureCode = failureCode;
        this.createdAtMs = createdAtMs;
        this.updatedAtMs = updatedAtMs;
    }

    private ConversationMessage(Parcel in) {
        schemaVersion = in.readInt();
        conversationId = in.readString();
        messageId = in.readString();
        sequenceNo = in.readLong();
        role = in.readInt();
        status = in.readInt();
        channel = in.readInt();
        text = in.readString();
        languageTag = in.readString();
        conversationTaskId = in.readString();
        failureCode = in.readInt();
        createdAtMs = in.readLong();
        updatedAtMs = in.readLong();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(conversationId);
        dest.writeString(messageId);
        dest.writeLong(sequenceNo);
        dest.writeInt(role);
        dest.writeInt(status);
        dest.writeInt(channel);
        dest.writeString(text);
        dest.writeString(languageTag);
        dest.writeString(conversationTaskId);
        dest.writeInt(failureCode);
        dest.writeLong(createdAtMs);
        dest.writeLong(updatedAtMs);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationMessage> CREATOR = new Creator<>() {
        @Override public ConversationMessage createFromParcel(Parcel in) {
            return new ConversationMessage(in);
        }
        @Override public ConversationMessage[] newArray(int size) {
            return new ConversationMessage[size];
        }
    };
}
