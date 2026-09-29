package com.matrix.agent.api.interaction;

import android.os.Parcel;
import android.os.Parcelable;

/** Primary-finger coordinates in display pixels. No key, text, window or application content. */
public record OverlayPointerSample(int schemaVersion, long gestureId, int phase,
        float x, float y, int rotation, long uptimeMillis) implements Parcelable {
    public static final int DOWN = 0, MOVE = 1, UP = 2, CANCEL = 3;
    public boolean valid() {
        return schemaVersion >= 13 && gestureId >= 0 && phase >= DOWN && phase <= CANCEL
                && Float.isFinite(x) && Float.isFinite(y) && rotation >= 0 && rotation <= 3
                && uptimeMillis >= gestureId;
    }
    private OverlayPointerSample(Parcel in) {
        this(in.readInt(), in.readLong(), in.readInt(), in.readFloat(), in.readFloat(), in.readInt(), in.readLong());
    }
    @Override public void writeToParcel(Parcel out, int flags) {
        out.writeInt(schemaVersion); out.writeLong(gestureId); out.writeInt(phase);
        out.writeFloat(x); out.writeFloat(y); out.writeInt(rotation); out.writeLong(uptimeMillis);
    }
    @Override public int describeContents() { return 0; }
    public static final Creator<OverlayPointerSample> CREATOR = new Creator<>() {
        @Override public OverlayPointerSample createFromParcel(Parcel in) { return new OverlayPointerSample(in); }
        @Override public OverlayPointerSample[] newArray(int size) { return new OverlayPointerSample[size]; }
    };
}
