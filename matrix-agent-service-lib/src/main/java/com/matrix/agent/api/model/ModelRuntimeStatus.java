package com.matrix.agent.api.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 端侧/云端运行时状态摘要。 */
public final class ModelRuntimeStatus implements Parcelable {

    /** backend 取值：枚举随部署形态追加。 */
    public static final int BACKEND_NONE = 0;
    public static final int BACKEND_CLOUD = 1;
    public static final int BACKEND_ON_DEVICE = 2;

    public final int schemaVersion;
    public final String activeModelId;
    public final boolean ready;
    public final int backend;
    public final int lastErrorCode;

    public ModelRuntimeStatus(String activeModelId, boolean ready, int backend,
            int lastErrorCode) {
        this(ParcelSchema.CURRENT, activeModelId, ready, backend, lastErrorCode);
    }

    public ModelRuntimeStatus(int schemaVersion, String activeModelId, boolean ready,
            int backend, int lastErrorCode) {
        this.schemaVersion = schemaVersion;
        this.activeModelId = activeModelId;
        this.ready = ready;
        this.backend = backend;
        this.lastErrorCode = lastErrorCode;
    }

    private ModelRuntimeStatus(Parcel in) {
        schemaVersion = in.readInt();
        activeModelId = in.readString();
        ready = in.readByte() != 0;
        backend = in.readInt();
        lastErrorCode = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(activeModelId);
        dest.writeByte((byte) (ready ? 1 : 0));
        dest.writeInt(backend);
        dest.writeInt(lastErrorCode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ModelRuntimeStatus> CREATOR = new Creator<>() {
        @Override
        public ModelRuntimeStatus createFromParcel(Parcel in) {
            return new ModelRuntimeStatus(in);
        }

        @Override
        public ModelRuntimeStatus[] newArray(int size) {
            return new ModelRuntimeStatus[size];
        }
    };
}
