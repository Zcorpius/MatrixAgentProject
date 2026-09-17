package com.matrix.agent.api.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 模型/下载异步操作句柄；完成结果经 IModelCallback 回调。 */
public final class ModelOperationHandle implements Parcelable {

    public static final int STATE_PENDING = 0;
    public static final int STATE_RUNNING = 1;
    public static final int STATE_SUCCEEDED = 2;
    public static final int STATE_FAILED = 3;
    public static final int STATE_CANCELLED = 4;

    public final int schemaVersion;
    public final String operationId;
    public final String modelId;
    public final int state;

    public ModelOperationHandle(String operationId, String modelId, int state) {
        this(ParcelSchema.CURRENT, operationId, modelId, state);
    }

    public ModelOperationHandle(int schemaVersion, String operationId, String modelId, int state) {
        this.schemaVersion = schemaVersion;
        this.operationId = operationId;
        this.modelId = modelId;
        this.state = state;
    }

    private ModelOperationHandle(Parcel in) {
        schemaVersion = in.readInt();
        operationId = in.readString();
        modelId = in.readString();
        state = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(operationId);
        dest.writeString(modelId);
        dest.writeInt(state);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ModelOperationHandle> CREATOR = new Creator<>() {
        @Override
        public ModelOperationHandle createFromParcel(Parcel in) {
            return new ModelOperationHandle(in);
        }

        @Override
        public ModelOperationHandle[] newArray(int size) {
            return new ModelOperationHandle[size];
        }
    };
}
