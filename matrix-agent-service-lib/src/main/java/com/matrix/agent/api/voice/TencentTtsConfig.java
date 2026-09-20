package com.matrix.agent.api.voice;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** Non-secret projection of the Host-owned Tencent Cloud TTS configuration. */
public final class TencentTtsConfig implements Parcelable {
    /** Tencent's documented standard ZhiYu voice; users may replace it with an entitled voice ID. */
    public static final int DEFAULT_VOICE_TYPE = 1001;
    public static final String DEFAULT_EMOTION = "neutral";
    public static final int DEFAULT_EMOTION_INTENSITY = 100;

    public final int schemaVersion;
    public final boolean configured;
    public final int voiceType;
    public final String emotionCategory;
    public final int emotionIntensity;

    public TencentTtsConfig(boolean configured, int voiceType, String emotionCategory,
            int emotionIntensity) {
        this(ParcelSchema.CURRENT, configured, voiceType, emotionCategory, emotionIntensity);
    }

    public TencentTtsConfig(int schemaVersion, boolean configured, int voiceType,
            String emotionCategory, int emotionIntensity) {
        this.schemaVersion = schemaVersion;
        this.configured = configured;
        this.voiceType = voiceType;
        this.emotionCategory = emotionCategory == null ? DEFAULT_EMOTION : emotionCategory;
        this.emotionIntensity = emotionIntensity;
    }

    private TencentTtsConfig(Parcel in) {
        schemaVersion = in.readInt();
        configured = in.readInt() != 0;
        voiceType = in.readInt();
        emotionCategory = in.readString();
        emotionIntensity = in.readInt();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(configured ? 1 : 0);
        dest.writeInt(voiceType);
        dest.writeString(emotionCategory);
        dest.writeInt(emotionIntensity);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<TencentTtsConfig> CREATOR = new Creator<>() {
        @Override public TencentTtsConfig createFromParcel(Parcel in) { return new TencentTtsConfig(in); }
        @Override public TencentTtsConfig[] newArray(int size) { return new TencentTtsConfig[size]; }
    };
}
