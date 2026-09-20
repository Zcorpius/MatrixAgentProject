package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 对话列表查询；owner 恒由 Host 从调用方推导，客户端只能筛自己的线程。 */
public final class ConversationListQuery implements Parcelable {

    public static final int DEFAULT_LIMIT = 30;
    public static final int MAX_LIMIT = 100;

    public final int schemaVersion;
    public final boolean includeArchived;
    public final int limit;

    public ConversationListQuery(boolean includeArchived, int limit) {
        this(ParcelSchema.CURRENT, includeArchived, limit);
    }

    public ConversationListQuery(int schemaVersion, boolean includeArchived, int limit) {
        this.schemaVersion = schemaVersion;
        this.includeArchived = includeArchived;
        this.limit = limit;
    }

    private ConversationListQuery(Parcel in) {
        schemaVersion = in.readInt();
        includeArchived = in.readByte() != 0;
        limit = in.readInt();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeByte((byte) (includeArchived ? 1 : 0));
        dest.writeInt(limit);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ConversationListQuery> CREATOR = new Creator<>() {
        @Override public ConversationListQuery createFromParcel(Parcel in) {
            return new ConversationListQuery(in);
        }
        @Override public ConversationListQuery[] newArray(int size) {
            return new ConversationListQuery[size];
        }
    };
}
