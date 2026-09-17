package com.matrix.agent.api.model;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/**
 * A controlled model-provisioning request.
 *
 * <p>The caller may select a model identifier for a code-signed provider preset. Endpoint
 * overrides are accepted only for Host-approved local/custom presets; headers and credential
 * references remain Host-owned and the credential stays in its keystore domain.</p>
 */
public final class ModelProvisionInput implements Parcelable {
    public final int schemaVersion;
    public final String providerId;
    /** Optional for providers with a Host default; required by providers such as Doubao. */
    public final String modelId;
    /** Optional local/custom endpoint override. The Host validates both preset and URI. */
    public final String endpoint;

    public ModelProvisionInput(String providerId, String modelId, String endpoint) {
        this(ParcelSchema.CURRENT, providerId, modelId, endpoint);
    }

    public ModelProvisionInput(int schemaVersion, String providerId, String modelId, String endpoint) {
        this.schemaVersion = schemaVersion;
        this.providerId = providerId;
        this.modelId = modelId;
        this.endpoint = endpoint;
    }

    private ModelProvisionInput(Parcel in) {
        schemaVersion = in.readInt();
        providerId = in.readString();
        modelId = in.readString();
        endpoint = in.readString();
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(providerId);
        dest.writeString(modelId);
        dest.writeString(endpoint);
    }

    @Override public int describeContents() { return 0; }

    public static final Creator<ModelProvisionInput> CREATOR = new Creator<>() {
        @Override public ModelProvisionInput createFromParcel(Parcel in) {
            return new ModelProvisionInput(in);
        }
        @Override public ModelProvisionInput[] newArray(int size) {
            return new ModelProvisionInput[size];
        }
    };
}
