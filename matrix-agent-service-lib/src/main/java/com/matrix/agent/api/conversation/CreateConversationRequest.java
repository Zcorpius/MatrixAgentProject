package com.matrix.agent.api.conversation;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 新建对话线程请求。title/languageTag 可空；owner 与 zone 恒由 Host 推导。 */
public final class CreateConversationRequest implements Parcelable {

    public final int schemaVersion;
    public final String title;
    public final String languageTag;

    public CreateConversationRequest(String title, String languageTag) {
        this(ParcelSchema.CURRENT, title, languageTag);
    }

    public CreateConversationRequest(int schemaVersion, String title, String languageTag) {
        this.schemaVersion = schemaVersion;
        this.title = title;
        this.languageTag = languageTag;
    }

    private CreateConversationRequest(Parcel in) {
        schemaVersion = in.readInt();
        title = in.readString();
        languageTag = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(title);
        dest.writeString(languageTag);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<CreateConversationRequest> CREATOR = new Creator<>() {
        @Override public CreateConversationRequest createFromParcel(Parcel in) {
            return new CreateConversationRequest(in);
        }
        @Override public CreateConversationRequest[] newArray(int size) {
            return new CreateConversationRequest[size];
        }
    };
}
