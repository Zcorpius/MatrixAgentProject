package com.matrix.agent.api.voice;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** Non-secret Tencent TTS options. Credentials travel separately in a one-shot pipe. */
public final class TencentTtsProvisionInput implements Parcelable {
    public final int schemaVersion;
    public final int voiceType;
    public final String emotionCategory;
    public final int emotionIntensity;

    public TencentTtsProvisionInput(int voiceType, String emotionCategory, int emotionIntensity) {
        this(ParcelSchema.CURRENT, voiceType, emotionCategory, emotionIntensity);
    }

    public TencentTtsProvisionInput(int schemaVersion, int voiceType, String emotionCategory,
            int emotionIntensity) {
        this.schemaVersion = schemaVersion;
        this.voiceType = voiceType;
        this.emotionCategory = emotionCategory;
        this.emotionIntensity = emotionIntensity;
    }

    private TencentTtsProvisionInput(Parcel in) {
        schemaVersion = in.readInt();
        voiceType = in.readInt();
        emotionCategory = in.readString();
        emotionIntensity = in.readInt();
    }
    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(voiceType);
        dest.writeString(emotionCategory);
        dest.writeInt(emotionIntensity);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<TencentTtsProvisionInput> CREATOR = new Creator<>() {
        @Override public TencentTtsProvisionInput createFromParcel(Parcel in) {
            return new TencentTtsProvisionInput(in);
        }
        @Override public TencentTtsProvisionInput[] newArray(int size) {
            return new TencentTtsProvisionInput[size];
        }
    };
}
