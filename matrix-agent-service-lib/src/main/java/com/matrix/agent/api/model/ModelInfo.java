package com.matrix.agent.api.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 模型元数据；不暴露本地文件路径（模型文件不能出 Service 边界）。 */
public final class ModelInfo implements Parcelable {

    public final int schemaVersion;
    public final String modelId;
    public final String displayName;
    public final String providerId;
    public final boolean active;
    public final boolean available;

    public ModelInfo(String modelId, String displayName, String providerId,
            boolean active, boolean available) {
        this(ParcelSchema.CURRENT, modelId, displayName, providerId, active, available);
    }

    public ModelInfo(int schemaVersion, String modelId, String displayName, String providerId,
            boolean active, boolean available) {
        this.schemaVersion = schemaVersion;
        this.modelId = modelId;
        this.displayName = displayName;
        this.providerId = providerId;
        this.active = active;
        this.available = available;
    }

    private ModelInfo(Parcel in) {
        schemaVersion = in.readInt();
        modelId = in.readString();
        displayName = in.readString();
        providerId = in.readString();
        active = in.readByte() != 0;
        available = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(modelId);
        dest.writeString(displayName);
        dest.writeString(providerId);
        dest.writeByte((byte) (active ? 1 : 0));
        dest.writeByte((byte) (available ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ModelInfo> CREATOR = new Creator<>() {
        @Override
        public ModelInfo createFromParcel(Parcel in) {
            return new ModelInfo(in);
        }

        @Override
        public ModelInfo[] newArray(int size) {
            return new ModelInfo[size];
        }
    };
}
