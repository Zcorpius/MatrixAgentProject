package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 受控上下文附件（输入交互增强 I6，v8）：用户显式选择、Host 摄取净化的模型上下文。
 *
 * <p>2A 阶段仅支持可提取文本的普通文件（text/* 白名单）；图片在 OCR 端口选型完成前
 * fail-closed（state=FAILED + UNSUPPORTED_MEDIA），不伪装为“图片已理解”。附件正文
 * （extractedText）只在 Host 进程内用于模型投影，绝不跨 Binder；本 DTO 只携带 chip
 * 渲染所需的安全元数据。提交成功后附件随消息冻结（linkedMessageId 语义在 Host 侧），
 * 草稿态附件随 clearUserData / 7 天回收清理。</p>
 */
public final class ConversationAttachment implements Parcelable {

    /** Host 已接受文件描述符，正在受控提取；此时不可提交。 */
    public static final int STATE_STAGING = 0;
    public static final int STATE_READY = 1;
    public static final int STATE_FAILED = 2;

    /** 文本提取失败的原因细分（errorCode 取 MatrixErrorCode 语义）。 */
    public static final int ERROR_UNSUPPORTED_MEDIA = 20;
    public static final int ERROR_TOO_LARGE = 21;
    public static final int ERROR_EMPTY_TEXT = 22;

    public final int schemaVersion;
    public final String attachmentId;
    /** 草稿归属会话；提交后由 Host 冻结到消息，conversationId 保留为派生事实。 */
    public final String conversationId;
    public final String mimeType;
    /** chip 展示名（Host 已截断/去路径）；原始文件名不回传。 */
    public final String safeDisplayName;
    public final long byteSize;
    /** STATE_READY / STATE_FAILED。 */
    public final int state;
    public final int errorCode;
    /** 提取出的文本字符数（chip 摘要用）；正文不跨 Binder。 */
    public final int extractedChars;
    public final long createdAtMs;

    public ConversationAttachment(String attachmentId, String conversationId,
            String mimeType, String safeDisplayName, long byteSize, int state,
            int errorCode, int extractedChars, long createdAtMs) {
        this(ParcelSchema.CURRENT, attachmentId, conversationId, mimeType,
                safeDisplayName, byteSize, state, errorCode, extractedChars, createdAtMs);
    }

    public ConversationAttachment(int schemaVersion, String attachmentId,
            String conversationId, String mimeType, String safeDisplayName,
            long byteSize, int state, int errorCode, int extractedChars,
            long createdAtMs) {
        this.schemaVersion = schemaVersion;
        this.attachmentId = attachmentId;
        this.conversationId = conversationId;
        this.mimeType = mimeType;
        this.safeDisplayName = safeDisplayName;
        this.byteSize = byteSize;
        this.state = state;
        this.errorCode = errorCode;
        this.extractedChars = extractedChars;
        this.createdAtMs = createdAtMs;
    }

    public boolean isReady() {
        return state == STATE_READY;
    }

    public boolean isStaging() {
        return state == STATE_STAGING;
    }

    private ConversationAttachment(Parcel in) {
        schemaVersion = in.readInt();
        attachmentId = in.readString();
        conversationId = in.readString();
        mimeType = in.readString();
        safeDisplayName = in.readString();
        byteSize = in.readLong();
        state = in.readInt();
        errorCode = in.readInt();
        extractedChars = in.readInt();
        createdAtMs = in.readLong();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(attachmentId);
        dest.writeString(conversationId);
        dest.writeString(mimeType);
        dest.writeString(safeDisplayName);
        dest.writeLong(byteSize);
        dest.writeInt(state);
        dest.writeInt(errorCode);
        dest.writeInt(extractedChars);
        dest.writeLong(createdAtMs);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationAttachment> CREATOR = new Creator<>() {
        @Override public ConversationAttachment createFromParcel(Parcel in) {
            return new ConversationAttachment(in);
        }
        @Override public ConversationAttachment[] newArray(int size) {
            return new ConversationAttachment[size];
        }
    };
}
