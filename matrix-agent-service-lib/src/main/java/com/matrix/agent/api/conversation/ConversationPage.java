package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 一页消息历史：messages 按 sequence 升序；hasMore 表示还有更早消息可翻。 */
public final class ConversationPage implements Parcelable {

    public final int schemaVersion;
    public final List<ConversationMessage> messages;
    public final boolean hasMore;
    /** 窗口外是否还有更早消息（v3 追加；旧页语义下 ≡ hasMore）。 */
    public final boolean hasBefore;
    /** 窗口外是否还有更新消息（v3 追加；旧页语义下为 false）。 */
    public final boolean hasAfter;
    /** 锚点是否存在（v3 追加；无锚点方法恒 true；不可定位时空页 + false，不泄漏存在性）。 */
    public final boolean anchorExists;

    public ConversationPage(List<ConversationMessage> messages, boolean hasMore) {
        this(ParcelSchema.CURRENT, messages, hasMore, hasMore, false, true);
    }

    public ConversationPage(int schemaVersion, List<ConversationMessage> messages,
            boolean hasMore) {
        this(schemaVersion, messages, hasMore, hasMore, false, true);
    }

    public ConversationPage(int schemaVersion, List<ConversationMessage> messages,
            boolean hasMore, boolean hasBefore, boolean hasAfter, boolean anchorExists) {
        this.schemaVersion = schemaVersion;
        this.messages = messages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        this.hasMore = hasMore;
        this.hasBefore = hasBefore;
        this.hasAfter = hasAfter;
        this.anchorExists = anchorExists;
    }

    private ConversationPage(Parcel in) {
        schemaVersion = in.readInt();
        ArrayList<ConversationMessage> items = new ArrayList<>();
        in.readTypedList(items, ConversationMessage.CREATOR);
        messages = Collections.unmodifiableList(items);
        hasMore = in.readByte() != 0;
        if (schemaVersion >= 3) {
            hasBefore = in.readByte() != 0;
            hasAfter = in.readByte() != 0;
            anchorExists = in.readByte() != 0;
        } else {
            hasBefore = hasMore;
            hasAfter = false;
            anchorExists = true;
        }
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeTypedList(messages);
        dest.writeByte((byte) (hasMore ? 1 : 0));
        dest.writeByte((byte) (hasBefore ? 1 : 0));
        dest.writeByte((byte) (hasAfter ? 1 : 0));
        dest.writeByte((byte) (anchorExists ? 1 : 0));
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationPage> CREATOR = new Creator<>() {
        @Override public ConversationPage createFromParcel(Parcel in) {
            return new ConversationPage(in);
        }
        @Override public ConversationPage[] newArray(int size) {
            return new ConversationPage[size];
        }
    };
}
