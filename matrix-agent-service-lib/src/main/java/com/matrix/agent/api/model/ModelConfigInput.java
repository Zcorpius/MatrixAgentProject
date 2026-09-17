package com.matrix.agent.api.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * 模型配置输入：仅受控 provider 枚举 + Keystore 密钥引用。
 * 不接受调用方传入的任意 URL、header 或明文 token；endpoint 由服务端按 providerId 派生。
 */
public final class ModelConfigInput implements Parcelable {

    public final int schemaVersion;
    /** 受控 provider 标识（服务端白名单内）。 */
    public final String providerId;
    /** Keystore 密钥引用（别名），不是明文 API Key。 */
    public final String apiKeyRef;

    public ModelConfigInput(String providerId, String apiKeyRef) {
        this(ParcelSchema.CURRENT, providerId, apiKeyRef);
    }

    public ModelConfigInput(int schemaVersion, String providerId, String apiKeyRef) {
        this.schemaVersion = schemaVersion;
        this.providerId = providerId;
        this.apiKeyRef = apiKeyRef;
    }

    private ModelConfigInput(Parcel in) {
        schemaVersion = in.readInt();
        providerId = in.readString();
        apiKeyRef = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(providerId);
        dest.writeString(apiKeyRef);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ModelConfigInput> CREATOR = new Creator<>() {
        @Override
        public ModelConfigInput createFromParcel(Parcel in) {
            return new ModelConfigInput(in);
        }

        @Override
        public ModelConfigInput[] newArray(int size) {
            return new ModelConfigInput[size];
        }
    };
}
