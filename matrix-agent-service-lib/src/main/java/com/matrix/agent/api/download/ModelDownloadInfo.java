package com.matrix.agent.api.download;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 单个模型的下载状态投影。 */
public final class ModelDownloadInfo implements Parcelable {

    public static final int DOWNLOAD_STATE_IDLE = 0;
    public static final int DOWNLOAD_STATE_DOWNLOADING = 1;
    public static final int DOWNLOAD_STATE_PAUSED = 2;
    public static final int DOWNLOAD_STATE_COMPLETED = 3;
    public static final int DOWNLOAD_STATE_FAILED = 4;

    public final int schemaVersion;
    public final String modelId;
    public final int state;
    public final long bytesDownloaded;
    public final long bytesTotal;
    public final int errorCode;
    /** 模型版本（v6 追加；Host 规格事实，无来源时为空串，客户端负责兜底展示）。 */
    public final String version;

    public ModelDownloadInfo(String modelId, int state, long bytesDownloaded, long bytesTotal,
            int errorCode) {
        this(ParcelSchema.CURRENT, modelId, state, bytesDownloaded, bytesTotal, errorCode, "");
    }

    public ModelDownloadInfo(String modelId, int state, long bytesDownloaded, long bytesTotal,
            int errorCode, String version) {
        this(ParcelSchema.CURRENT, modelId, state, bytesDownloaded, bytesTotal, errorCode, version);
    }

    public ModelDownloadInfo(int schemaVersion, String modelId, int state, long bytesDownloaded,
            long bytesTotal, int errorCode) {
        this(schemaVersion, modelId, state, bytesDownloaded, bytesTotal, errorCode, "");
    }

    public ModelDownloadInfo(int schemaVersion, String modelId, int state, long bytesDownloaded,
            long bytesTotal, int errorCode, String version) {
        this.schemaVersion = schemaVersion;
        this.modelId = modelId;
        this.state = state;
        this.bytesDownloaded = bytesDownloaded;
        this.bytesTotal = bytesTotal;
        this.errorCode = errorCode;
        this.version = version == null ? "" : version;
    }

    private ModelDownloadInfo(Parcel in) {
        schemaVersion = in.readInt();
        modelId = in.readString();
        state = in.readInt();
        bytesDownloaded = in.readLong();
        bytesTotal = in.readLong();
        errorCode = in.readInt();
        version = schemaVersion >= 6 ? orEmpty(in.readString()) : "";
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(modelId);
        dest.writeInt(state);
        dest.writeLong(bytesDownloaded);
        dest.writeLong(bytesTotal);
        dest.writeInt(errorCode);
        dest.writeString(version);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ModelDownloadInfo> CREATOR = new Creator<>() {
        @Override
        public ModelDownloadInfo createFromParcel(Parcel in) {
            return new ModelDownloadInfo(in);
        }

        @Override
        public ModelDownloadInfo[] newArray(int size) {
            return new ModelDownloadInfo[size];
        }
    };
}
