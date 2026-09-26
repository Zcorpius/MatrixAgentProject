package com.matrix.agent.api.handoff;

import android.os.Parcel;
import android.os.Parcelable;

/** Read-only activity hint; it grants no input lock and never pauses execution. */
public record ExternalUiActivitySnapshot(
        int schemaVersion,
        int displayId,
        long generation,
        int state) implements Parcelable {
    private ExternalUiActivitySnapshot(Parcel source) {
        this(source.readInt(), source.readInt(), source.readLong(), source.readInt());
    }

    @Override public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(schemaVersion);
        dest.writeInt(displayId);
        dest.writeLong(generation);
        dest.writeInt(state);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<ExternalUiActivitySnapshot> CREATOR = new Creator<>() {
        @Override public ExternalUiActivitySnapshot createFromParcel(Parcel source) { return new ExternalUiActivitySnapshot(source); }
        @Override public ExternalUiActivitySnapshot[] newArray(int size) { return new ExternalUiActivitySnapshot[size]; }
    };
}
