package com.matrix.agent.api.voice;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 受控会话请求：只含触发来源与语言等安全字段；不传 PCM、audio format 或原始中间结果。
 * languageTag 为 BCP-47（1..35），可空表示跟随系统。
 */
public final class VoiceSessionRequest implements Parcelable {

    /** 客户端"按住说话"入口。 */
    public static final int TRIGGER_PTT = 1;
    /** 其他受控触发（系统入口由服务端自己接入，不经此 API）。 */
    public static final int TRIGGER_OTHER = 2;

    public final int schemaVersion;
    public final int triggerSource;
    public final String languageTag;

    public VoiceSessionRequest(int triggerSource, String languageTag) {
        this(ParcelSchema.CURRENT, triggerSource, languageTag);
    }

    public VoiceSessionRequest(int schemaVersion, int triggerSource, String languageTag) {
        this.schemaVersion = schemaVersion;
        this.triggerSource = triggerSource;
        this.languageTag = languageTag;
    }

    private VoiceSessionRequest(Parcel in) {
        schemaVersion = in.readInt();
        triggerSource = in.readInt();
        languageTag = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(triggerSource);
        dest.writeString(languageTag);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<VoiceSessionRequest> CREATOR = new Creator<>() {
        @Override
        public VoiceSessionRequest createFromParcel(Parcel in) {
            return new VoiceSessionRequest(in);
        }

        @Override
        public VoiceSessionRequest[] newArray(int size) {
            return new VoiceSessionRequest[size];
        }
    };
}
