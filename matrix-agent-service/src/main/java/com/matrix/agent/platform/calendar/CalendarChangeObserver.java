package com.matrix.agent.platform.calendar;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract;

/** Debounced invalidation only. All source reads still run through the calendar capability. */
public final class CalendarChangeObserver implements AutoCloseable {
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { handler.removeCallbacks(changed); handler.postDelayed(changed, 750); }
    };
    public CalendarChangeObserver(Context context, Runnable changed) {
        this.context = context.getApplicationContext(); this.changed = changed;
        context.getContentResolver().registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer);
    }
    @Override public void close() { handler.removeCallbacks(changed); context.getContentResolver().unregisterContentObserver(observer); }
}
