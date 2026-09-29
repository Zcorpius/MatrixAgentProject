package com.matrix.agent.schedule.android;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import com.matrix.agent.schedule.domain.ClockSample;
import java.time.Instant;
import java.time.ZoneId;

public final class AndroidScheduleClock {
    private final Context context;
    public AndroidScheduleClock(Context context) { this.context = context.getApplicationContext(); }
    public ClockSample sample() {
        // BOOT_COUNT is stable across Host process death and changes on device reboot.
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot < 0) throw new IllegalStateException("boot identity unavailable");
        return new ClockSample(Instant.now(), SystemClock.elapsedRealtime(), Integer.toString(boot), ZoneId.systemDefault());
    }
}
