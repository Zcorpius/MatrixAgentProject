package com.matrix.agent.api.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 连通性测试结果；sanitizedMessage 不含 API Key、Authorization 或完整 endpoint。 */
public final class ConnectionTestResult implements Parcelable {

    public final int schemaVersion;
    public final boolean success;
    public final long latencyMs;
    public final int errorCode;
    public final String sanitizedMessage;

    public ConnectionTestResult(boolean success, long latencyMs, int errorCode,
            String sanitizedMessage) {
        this(ParcelSchema.CURRENT, success, latencyMs, errorCode, sanitizedMessage);
    }

    public ConnectionTestResult(int schemaVersion, boolean success, long latencyMs,
            int errorCode, String sanitizedMessage) {
        this.schemaVersion = schemaVersion;
        this.success = success;
        this.latencyMs = latencyMs;
        this.errorCode = errorCode;
        this.sanitizedMessage = sanitizedMessage;
    }

    private ConnectionTestResult(Parcel in) {
        schemaVersion = in.readInt();
        success = in.readByte() != 0;
        latencyMs = in.readLong();
        errorCode = in.readInt();
        sanitizedMessage = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeByte((byte) (success ? 1 : 0));
        dest.writeLong(latencyMs);
        dest.writeInt(errorCode);
        dest.writeString(sanitizedMessage);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ConnectionTestResult> CREATOR = new Creator<>() {
        @Override
        public ConnectionTestResult createFromParcel(Parcel in) {
            return new ConnectionTestResult(in);
        }

        @Override
        public ConnectionTestResult[] newArray(int size) {
            return new ConnectionTestResult[size];
        }
    };
}
