package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 会话草稿（输入交互增强 I4，v7）：Host 加密持久化的编辑中输入。
 *
 * <p>草稿是同一 {@code draftInstanceId} 内按 {@code revision} 单调递增的
 * last-write-wins 流，不是业务提交——不携带 clientOperationId、不携带 owner（scope
 * 由 Host 从 Binder 调用方推导）、不进入模型上下文/审计/自动标题。成功提交后 Host
 * 在受理事务内消费该次提交冻结的 instance 并落 tombstone：携带旧 instance 的迟到保存会被拒绝，
 * 已发送内容不得复活为草稿（§7.2 双层防线的 Host 侧半边）。</p>
 */
public final class ConversationDraft implements Parcelable {

    public final int schemaVersion;
    public final String conversationId;
    /** 高熵实例 id（客户端生成）；一次“从空开始编辑”的生命周期标识。 */
    public final String draftInstanceId;
    /** 同 instance 内单调递增；Host 只接受更大值。 */
    public final long revision;
    /**可为空串但从不为 null；上限由 Host 校验（12 KiB UTF-8）。 */
    public final String text;
    public final int selectionStart;
    public final int selectionEnd;
    public final long updatedAtMs;

    public ConversationDraft(String conversationId, String draftInstanceId, long revision,
            String text, int selectionStart, int selectionEnd, long updatedAtMs) {
        this(ParcelSchema.CURRENT, conversationId, draftInstanceId, revision, text,
                selectionStart, selectionEnd, updatedAtMs);
    }

    public ConversationDraft(int schemaVersion, String conversationId, String draftInstanceId,
            long revision, String text, int selectionStart, int selectionEnd,
            long updatedAtMs) {
        this.schemaVersion = schemaVersion;
        this.conversationId = conversationId;
        this.draftInstanceId = draftInstanceId;
        this.revision = revision;
        this.text = text == null ? "" : text;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
        this.updatedAtMs = updatedAtMs;
    }

    private ConversationDraft(Parcel in) {
        schemaVersion = in.readInt();
        conversationId = in.readString();
        draftInstanceId = in.readString();
        revision = in.readLong();
        text = in.readString();
        selectionStart = in.readInt();
        selectionEnd = in.readInt();
        updatedAtMs = in.readLong();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(conversationId);
        dest.writeString(draftInstanceId);
        dest.writeLong(revision);
        dest.writeString(text);
        dest.writeInt(selectionStart);
        dest.writeInt(selectionEnd);
        dest.writeLong(updatedAtMs);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationDraft> CREATOR = new Creator<>() {
        @Override public ConversationDraft createFromParcel(Parcel in) {
            return new ConversationDraft(in);
        }
        @Override public ConversationDraft[] newArray(int size) {
            return new ConversationDraft[size];
        }
    };
}
