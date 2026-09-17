package com.matrix.agent.api.download;

import android.os.Parcel;
import android.os.Parcelable;

import com.matrix.agent.api.common.ParcelSchema;

/** 模型市场目录项（本地已验签缓存的投影；release 只接受目录内带签名/hash/版本的 modelId）。 */
public final class ModelCatalogItem implements Parcelable {

    public final int schemaVersion;
    public final String catalogModelId;
    public final String displayName;
    public final String version;
    public final long sizeBytes;
    public final boolean installed;

    public ModelCatalogItem(String catalogModelId, String displayName, String version,
            long sizeBytes, boolean installed) {
        this(ParcelSchema.CURRENT, catalogModelId, displayName, version, sizeBytes, installed);
    }

    public ModelCatalogItem(int schemaVersion, String catalogModelId, String displayName,
            String version, long sizeBytes, boolean installed) {
        this.schemaVersion = schemaVersion;
        this.catalogModelId = catalogModelId;
        this.displayName = displayName;
        this.version = version;
        this.sizeBytes = sizeBytes;
        this.installed = installed;
    }

    private ModelCatalogItem(Parcel in) {
        schemaVersion = in.readInt();
        catalogModelId = in.readString();
        displayName = in.readString();
        version = in.readString();
        sizeBytes = in.readLong();
        installed = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeString(catalogModelId);
        dest.writeString(displayName);
        dest.writeString(version);
        dest.writeLong(sizeBytes);
        dest.writeByte((byte) (installed ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ModelCatalogItem> CREATOR = new Creator<>() {
        @Override
        public ModelCatalogItem createFromParcel(Parcel in) {
            return new ModelCatalogItem(in);
        }

        @Override
        public ModelCatalogItem[] newArray(int size) {
            return new ModelCatalogItem[size];
        }
    };
}
