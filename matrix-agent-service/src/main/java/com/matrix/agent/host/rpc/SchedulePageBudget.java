package com.matrix.agent.host.rpc;

import android.os.Parcel;
import android.os.Parcelable;

/** Counts actual Parcel bytes, leaving headroom for Binder framing and simultaneous transactions. */
final class SchedulePageBudget {
    private static final int MAX_BYTES = 256 * 1024;
    private int used = 1024;
    boolean include(Parcelable item) {
        Parcel parcel = Parcel.obtain();
        try {
            item.writeToParcel(parcel, 0);
            int bytes = parcel.dataSize() + 8;
            if (bytes > MAX_BYTES - 1024) throw new IllegalArgumentException("单条计划记录超出传输上限");
            if (bytes > MAX_BYTES - used) return false;
            used += bytes; return true;
        } finally { parcel.recycle(); }
    }
}
