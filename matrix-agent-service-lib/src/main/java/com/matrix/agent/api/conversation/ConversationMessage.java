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
    /** v5 追加：INPUT_PRIMARY / INPUT_STEER（附属输入注记用）。 */
    public final int inputKind;
    /** 可空（v5）：steer 宿主的主用户消息 id。 */
    public final String steerHostUserMessageId;
    /** STEER_DELIVERY_*（v5）；非 steer 行为 PENDING 值无意义。 */
    public final int steerDeliveryState;
    /** v5 追加：宿主任务的能力事实轨迹（仅用户消息携带；旧 schema 空数组）。 */
    public final java.util.List<CapabilityTraceEntry> executionTraces;
    /**
     * v8 追加（输入交互增强 I6）：随本条用户消息提交的受控附件元数据（chip 回放）；
     * 正文已由 Host 净化并入模型投影，不随 DTO 携带。旧 schema / 无附件为空列表。
     * 仅 INPUT_PRIMARY 行可携带；steer 附属输入不挂附件。
     */
    public final java.util.List<ConversationAttachment> contextAttachments;

    public ConversationMessage(String conversationId, String messageId, long sequenceNo,
            int role, int status, int channel, String text, String languageTag,
            String conversationTaskId, int failureCode, long createdAtMs, long updatedAtMs) {
        this(ParcelSchema.CURRENT, conversationId, messageId, sequenceNo, role, status, channel,
                text, languageTag, conversationTaskId, failureCode, createdAtMs, updatedAtMs,
                INPUT_PRIMARY, null, STEER_DELIVERY_PENDING, null);
    }

    public ConversationMessage(int schemaVersion, String conversationId, String messageId,
            long sequenceNo, int role, int status, int channel, String text, String languageTag,
            String conversationTaskId, int failureCode, long createdAtMs, long updatedAtMs) {
        this(schemaVersion, conversationId, messageId, sequenceNo, role, status, channel,
                text, languageTag, conversationTaskId, failureCode, createdAtMs, updatedAtMs,
                INPUT_PRIMARY, null, STEER_DELIVERY_PENDING, null);
    }

    public ConversationMessage(int schemaVersion, String conversationId, String messageId,
            long sequenceNo, int role, int status, int channel, String text, String languageTag,
            String conversationTaskId, int failureCode, long createdAtMs, long updatedAtMs,
            int inputKind, String steerHostUserMessageId, int steerDeliveryState,
            java.util.List<CapabilityTraceEntry> executionTraces) {
        this(schemaVersion, conversationId, messageId, sequenceNo, role, status, channel,
                text, languageTag, conversationTaskId, failureCode, createdAtMs, updatedAtMs,
                inputKind, steerHostUserMessageId, steerDeliveryState, executionTraces, null);
    }

    public ConversationMessage(int schemaVersion, String conversationId, String messageId,
            long sequenceNo, int role, int status, int channel, String text, String languageTag,
            String conversationTaskId, int failureCode, long createdAtMs, long updatedAtMs,
            int inputKind, String steerHostUserMessageId, int steerDeliveryState,
            java.util.List<CapabilityTraceEntry> executionTraces,
            java.util.List<ConversationAttachment> contextAttachments) {
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
        this.inputKind = inputKind;
        this.steerHostUserMessageId = steerHostUserMessageId;
        this.steerDeliveryState = steerDeliveryState;
        this.executionTraces = executionTraces == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(
                        new java.util.ArrayList<>(executionTraces));
        this.contextAttachments = contextAttachments == null
                ? java.util.Collections.emptyList()
                : java.util.Collections.unmodifiableList(
                        new java.util.ArrayList<>(contextAttachments));
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
        if (schemaVersion >= 5) {
            inputKind = in.readInt();
            steerHostUserMessageId = in.readString();
            steerDeliveryState = in.readInt();
            java.util.ArrayList<CapabilityTraceEntry> traces =
                    new java.util.ArrayList<>();
            in.readTypedList(traces, CapabilityTraceEntry.CREATOR);
            executionTraces = java.util.Collections.unmodifiableList(traces);
        } else {
            inputKind = INPUT_PRIMARY;
            steerHostUserMessageId = null;
            steerDeliveryState = STEER_DELIVERY_PENDING;
            executionTraces = java.util.Collections.emptyList();
        }
        if (schemaVersion >= 8) {
            java.util.ArrayList<ConversationAttachment> attachments =
                    new java.util.ArrayList<>();
            in.readTypedList(attachments, ConversationAttachment.CREATOR);
            contextAttachments = java.util.Collections.unmodifiableList(attachments);
        } else {
            contextAttachments = java.util.Collections.emptyList();
        }
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
        dest.writeInt(inputKind);
        dest.writeString(steerHostUserMessageId);
        dest.writeInt(steerDeliveryState);
        dest.writeTypedList(executionTraces);
        dest.writeTypedList(contextAttachments);
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
