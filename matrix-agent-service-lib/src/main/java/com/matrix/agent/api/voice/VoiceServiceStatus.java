package com.matrix.agent.api.voice;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 语音服务整体状态（受控启停 + 当前会话摘要）。 */
public final class VoiceServiceStatus implements Parcelable {

    /** 会话状态对齐 VoiceSessionState 状态机（IDLE/LISTENING/.../SPEAKING）。 */
    public static final int SESSION_IDLE = 0;
    public static final int SESSION_LISTENING = 1;
    public static final int SESSION_THINKING = 2;
    public static final int SESSION_SPEAKING = 3;

    public final int schemaVersion;
    public final boolean enabled;
    public final int sessionState;
    public final String currentSessionId;
    public final int errorCode;

    public VoiceServiceStatus(boolean enabled, int sessionState, String currentSessionId,
            int errorCode) {
        this(ParcelSchema.CURRENT, enabled, sessionState, currentSessionId, errorCode);
    }

    public VoiceServiceStatus(int schemaVersion, boolean enabled, int sessionState,
            String currentSessionId, int errorCode) {
        this.schemaVersion = schemaVersion;
        this.enabled = enabled;
        this.sessionState = sessionState;
        this.currentSessionId = currentSessionId;
        this.errorCode = errorCode;
    }

    private VoiceServiceStatus(Parcel in) {
        schemaVersion = in.readInt();
        enabled = in.readByte() != 0;
        sessionState = in.readInt();
        currentSessionId = in.readString();
        errorCode = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeByte((byte) (enabled ? 1 : 0));
        dest.writeInt(sessionState);
        dest.writeString(currentSessionId);
        dest.writeInt(errorCode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<VoiceServiceStatus> CREATOR = new Creator<>() {
        @Override
        public VoiceServiceStatus createFromParcel(Parcel in) {
            return new VoiceServiceStatus(in);
        }

        @Override
        public VoiceServiceStatus[] newArray(int size) {
            return new VoiceServiceStatus[size];
        }
    };
}
