package com.matrix.agent.api.media;

import android.os.Parcel;
import android.os.Parcelable;
import com.matrix.agent.api.common.ParcelSchema;

/** Available choices and the route currently selected by the MEDIA product strategy. */
public final class MediaOutputSnapshot implements Parcelable {
    public static final int UNKNOWN = 0;
    public static final int BLUETOOTH = 1;
    public static final int LOCAL_HEADSET = 2;
    public static final int SPEAKER = 3;

    public final int schemaVersion;
    public final long revision;
    public final int selected;
    public final boolean bluetoothAvailable;
    public final boolean localHeadsetAvailable;
    public final boolean speakerAvailable;
    /** Empty on success; a failed selection returns a human-readable reason. */
    public final String message;

    public MediaOutputSnapshot(long revision, int selected, boolean bluetoothAvailable,
            boolean localHeadsetAvailable, boolean speakerAvailable, String message) {
        this(ParcelSchema.CURRENT, revision, selected, bluetoothAvailable,
                localHeadsetAvailable, speakerAvailable, message);
    }

    private MediaOutputSnapshot(int schemaVersion, long revision, int selected,
            boolean bluetoothAvailable, boolean localHeadsetAvailable,
            boolean speakerAvailable, String message) {
        this.schemaVersion = schemaVersion;
        this.revision = revision;
        this.selected = selected;
        this.bluetoothAvailable = bluetoothAvailable;
        this.localHeadsetAvailable = localHeadsetAvailable;
        this.speakerAvailable = speakerAvailable;
        this.message = message == null ? "" : message;
    }

    private MediaOutputSnapshot(Parcel in) {
        this(in.readInt(), in.readLong(), in.readInt(), in.readInt() != 0,
                in.readInt() != 0, in.readInt() != 0, in.readString());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeLong(revision);
        dest.writeInt(selected);
        dest.writeInt(bluetoothAvailable ? 1 : 0);
        dest.writeInt(localHeadsetAvailable ? 1 : 0);
        dest.writeInt(speakerAvailable ? 1 : 0);
        dest.writeString(message);
    }

    @Override public int describeContents() { return 0; }
    public static final Creator<MediaOutputSnapshot> CREATOR = new Creator<>() {
        @Override public MediaOutputSnapshot createFromParcel(Parcel in) {
            return new MediaOutputSnapshot(in);
        }
        @Override public MediaOutputSnapshot[] newArray(int size) {
            return new MediaOutputSnapshot[size];
        }
    };
}
