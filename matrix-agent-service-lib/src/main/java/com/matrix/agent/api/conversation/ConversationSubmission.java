package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.common.ParcelSchema;

/**
 * sendText / submitTextOrAppend 的稳定返回：被持久化的用户消息投影 + 受理结果码。
 * code != SUCCESS 时通常消息未落库（恢复未完成 / 校验失败 / 过载），taskId 字段为 null；
 * {@link #OUTCOME_STEER_DELIVERY_FAILED} 是刻意的例外：审计 steer 行已落库并收敛失败，
 * 但运行时没有接收它。
 *
 * <p>v7 追加 {@link #outcome}：submitTextOrAppend 在 steer 与主轮次之间的 Host 原子
 * 判定结果。旧 Host 写出的行 outcome 恒为 {@link #OUTCOME_UNSPECIFIED}——它只在
 * code == SUCCESS 时有区分意义，其余情况以 code 为准。</p>
 */
public final class ConversationSubmission implements Parcelable {

    /** 旧 sendText 或未知来源：不携带统一判定语义。 */
    public static final int OUTCOME_UNSPECIFIED = 0;
    /** 建立了新的 INPUT_PRIMARY 用户消息与任务（或幂等命中既有主轮次）。 */
    public static final int OUTCOME_PRIMARY_ACCEPTED = 1;
    /** 并入了当前运行中宿主任务（或幂等命中既有 steer 行）；不新建任务。 */
    public static final int OUTCOME_STEER_ACCEPTED = 2;
    /** 请求本身有效但当前状态不接受（如取消竞争中）；未落任何行。 */
    public static final int OUTCOME_INVALID_STATE = 3;
    /** 请求被校验/策略拒绝；未落任何行。 */
    public static final int OUTCOME_REJECTED = 4;
    /** Steer 审计行已落库但运行时明确拒绝投递；草稿不能被消费，调用方可修订重试。 */
    public static final int OUTCOME_STEER_DELIVERY_FAILED = 5;

    public final int schemaVersion;
    public final int code;
    public final String conversationId;
    public final String userMessageId;
    public final String conversationTaskId;
    public final long sequenceNo;
    /** 同 clientOperationId 重放时为 true：返回的是既有消息，任务不会二次执行。 */
    public final boolean replay;
    /** v7：submitTextOrAppend 的原子判定结果（OUTCOME_*）。 */
    public final int outcome;

    public ConversationSubmission(int code, String conversationId, String userMessageId,
            String conversationTaskId, long sequenceNo, boolean replay) {
        this(ParcelSchema.CURRENT, code, conversationId, userMessageId, conversationTaskId,
                sequenceNo, replay, OUTCOME_UNSPECIFIED);
    }

    public ConversationSubmission(int schemaVersion, int code, String conversationId,
            String userMessageId, String conversationTaskId, long sequenceNo, boolean replay) {
        this(schemaVersion, code, conversationId, userMessageId, conversationTaskId,
                sequenceNo, replay, OUTCOME_UNSPECIFIED);
    }

    public ConversationSubmission(int code, String conversationId, String userMessageId,
            String conversationTaskId, long sequenceNo, boolean replay, int outcome) {
        this(ParcelSchema.CURRENT, code, conversationId, userMessageId, conversationTaskId,
                sequenceNo, replay, outcome);
    }

    public ConversationSubmission(int schemaVersion, int code, String conversationId,
            String userMessageId, String conversationTaskId, long sequenceNo, boolean replay,
            int outcome) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.conversationId = conversationId;
        this.userMessageId = userMessageId;
        this.conversationTaskId = conversationTaskId;
        this.sequenceNo = sequenceNo;
        this.replay = replay;
        this.outcome = outcome;
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
        outcome = schemaVersion >= 7 ? in.readInt() : OUTCOME_UNSPECIFIED;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeString(conversationId);
        dest.writeString(userMessageId);
        dest.writeString(conversationTaskId);
        dest.writeLong(sequenceNo);
        dest.writeByte((byte) (replay ? 1 : 0));
        dest.writeInt(outcome);
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
