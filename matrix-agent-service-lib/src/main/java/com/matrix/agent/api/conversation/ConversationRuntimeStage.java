package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 运行阶段事件（输入交互增强 I3，v7）：Host 真实 Engine 事件驱动的受限进度投影。
 *
 * <p>第一版只有三个严格可观测阶段（设计文档 §6.1）——阶段不伪造：一次原生
 * tool-calling 模型调用不拆成“理解/规划”两段，Provider 内部同步 readback 不假装
 * 成独立的“核验中”。{@code safeLabel} 已由 Host 脱敏且长度受限（EXECUTING 携带
 * capability 友好名）；不携带原始 prompt、reasoning、参数或工具返回。</p>
 *
 * <p>同一 {@code conversationTaskId} 的 {@code generation} 单调递增；客户端丢弃旧
 * generation 与未知 task 的迟到事件。{@code snapshot=true} 表示订阅受理时的当前
 * 快照重放，不是一次新的状态转换。</p>
 */
public final class ConversationRuntimeStage implements Parcelable {

    public static final int STAGE_QUEUED = 1;
    public static final int STAGE_PLANNING = 2;
    public static final int STAGE_EXECUTING = 3;

    public final int schemaVersion;
    public final String conversationId;
    public final String conversationTaskId;
    public final long generation;
    public final int stage;
    /** 已脱敏、长度受限的补充标签；仅 EXECUTING 携带 capability 友好名，其余为空串。 */
    public final String safeLabel;
    public final long occurredAtMs;
    /** true = 订阅受理后的当前快照重放。 */
    public final boolean snapshot;

    public ConversationRuntimeStage(String conversationId, String conversationTaskId,
            long generation, int stage, String safeLabel, long occurredAtMs,
            boolean snapshot) {
        this(ParcelSchema.CURRENT, conversationId, conversationTaskId, generation, stage,
                safeLabel, occurredAtMs, snapshot);
    }

    public ConversationRuntimeStage(int schemaVersion, String conversationId,
            String conversationTaskId, long generation, int stage, String safeLabel,
            long occurredAtMs, boolean snapshot) {
        this.schemaVersion = schemaVersion;
        this.conversationId = conversationId;
        this.conversationTaskId = conversationTaskId;
        this.generation = generation;
        this.stage = stage;
        this.safeLabel = safeLabel == null ? "" : safeLabel;
        this.occurredAtMs = occurredAtMs;
        this.snapshot = snapshot;
    }

    private ConversationRuntimeStage(Parcel in) {
        schemaVersion = in.readInt();
        conversationId = in.readString();
        conversationTaskId = in.readString();
        generation = in.readLong();
        stage = in.readInt();
        safeLabel = in.readString();
        occurredAtMs = in.readLong();
        snapshot = in.readByte() != 0;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(conversationId);
        dest.writeString(conversationTaskId);
        dest.writeLong(generation);
        dest.writeInt(stage);
        dest.writeString(safeLabel);
        dest.writeLong(occurredAtMs);
        dest.writeByte((byte) (snapshot ? 1 : 0));
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationRuntimeStage> CREATOR = new Creator<>() {
        @Override public ConversationRuntimeStage createFromParcel(Parcel in) {
            return new ConversationRuntimeStage(in);
        }
        @Override public ConversationRuntimeStage[] newArray(int size) {
            return new ConversationRuntimeStage[size];
        }
    };
}
