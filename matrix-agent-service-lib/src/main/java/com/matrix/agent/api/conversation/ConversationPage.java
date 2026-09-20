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

    public ConversationPage(List<ConversationMessage> messages, boolean hasMore) {
        this(ParcelSchema.CURRENT, messages, hasMore);
    }

    public ConversationPage(int schemaVersion, List<ConversationMessage> messages,
            boolean hasMore) {
        this.schemaVersion = schemaVersion;
        this.messages = messages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(messages));
        this.hasMore = hasMore;
    }

    private ConversationPage(Parcel in) {
        schemaVersion = in.readInt();
        ArrayList<ConversationMessage> items = new ArrayList<>();
        in.readTypedList(items, ConversationMessage.CREATOR);
        messages = Collections.unmodifiableList(items);
        hasMore = in.readByte() != 0;
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeTypedList(messages);
        dest.writeByte((byte) (hasMore ? 1 : 0));
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
