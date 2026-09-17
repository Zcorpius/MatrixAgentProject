package com.matrix.agent.api.voice;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 语音控制操作的稳定返回。 */
public final class VoiceOperationResult implements Parcelable {

    public final int schemaVersion;
    public final int code;
    public final String clientOperationId;
    public final String sessionId;

    public VoiceOperationResult(int code, String clientOperationId, String sessionId) {
        this(ParcelSchema.CURRENT, code, clientOperationId, sessionId);
    }

    public VoiceOperationResult(int schemaVersion, int code, String clientOperationId,
            String sessionId) {
        this.schemaVersion = schemaVersion;
        this.code = code;
        this.clientOperationId = clientOperationId;
        this.sessionId = sessionId;
    }

    private VoiceOperationResult(Parcel in) {
        schemaVersion = in.readInt();
        code = in.readInt();
        clientOperationId = in.readString();
        sessionId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(code);
        dest.writeString(clientOperationId);
        dest.writeString(sessionId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<VoiceOperationResult> CREATOR = new Creator<>() {
        @Override
        public VoiceOperationResult createFromParcel(Parcel in) {
            return new VoiceOperationResult(in);
        }

        @Override
        public VoiceOperationResult[] newArray(int size) {
            return new VoiceOperationResult[size];
        }
    };
}
