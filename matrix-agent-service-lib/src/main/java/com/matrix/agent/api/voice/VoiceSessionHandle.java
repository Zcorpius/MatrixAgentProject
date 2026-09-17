package com.matrix.agent.api.voice;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 受控会话句柄（startUserInitiatedSession 返回）。 */
public final class VoiceSessionHandle implements Parcelable {

    public final int schemaVersion;
    public final String sessionId;
    public final String clientOperationId;
    public final int state;

    public VoiceSessionHandle(String sessionId, String clientOperationId, int state) {
        this(ParcelSchema.CURRENT, sessionId, clientOperationId, state);
    }

    public VoiceSessionHandle(int schemaVersion, String sessionId, String clientOperationId,
            int state) {
        this.schemaVersion = schemaVersion;
        this.sessionId = sessionId;
        this.clientOperationId = clientOperationId;
        this.state = state;
    }

    private VoiceSessionHandle(Parcel in) {
        schemaVersion = in.readInt();
        sessionId = in.readString();
        clientOperationId = in.readString();
        state = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(sessionId);
        dest.writeString(clientOperationId);
        dest.writeInt(state);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<VoiceSessionHandle> CREATOR = new Creator<>() {
        @Override
        public VoiceSessionHandle createFromParcel(Parcel in) {
            return new VoiceSessionHandle(in);
        }

        @Override
        public VoiceSessionHandle[] newArray(int size) {
            return new VoiceSessionHandle[size];
        }
    };
}
