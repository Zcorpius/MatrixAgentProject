package com.matrix.agent.platform.weather;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import com.matrix.agent.identity.AgentRequest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A one-shot city-grade fix, bounded by both the step deadline and an eight-second hard cap. */
public final class AndroidCurrentLocationProvider implements LocationProviderPort {
    private static final long MAX_AGE_MILLIS = 5 * 60_000L;
    private static final float MAX_ACCURACY_METERS = 5_000f;
    private final Context context;
    private final LocationManager manager;

    public AndroidCurrentLocationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.manager = context.getSystemService(LocationManager.class);
    }

    @Override public Fix current(AgentRequest request) throws WeatherFailure, InterruptedException {
        if (manager == null || !manager.isLocationEnabled()) throw new WeatherFailure("LOCATION_DISABLED");
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw new WeatherFailure("LOCATION_PERMISSION_DENIED");
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
                && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw new WeatherFailure("BACKGROUND_LOCATION_DENIED");
        String provider = manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                : manager.getAllProviders().contains("fused") && manager.isProviderEnabled("fused") ? "fused"
                : manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : "";
        if (provider.isEmpty()) throw new WeatherFailure("LOCATION_PROVIDER_UNAVAILABLE");
        var signal = new CancellationSignal();
        var latch = new CountDownLatch(1);
        var result = new AtomicReference<Location>();
        Runnable abort = signal::cancel;
        android.location.LocationListener legacy = location -> { result.set(location); latch.countDown(); };
        request.getCancellationToken().registerAbortHook(abort);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                manager.getCurrentLocation(provider, signal, Runnable::run, fix -> { result.set(fix); latch.countDown(); });
            else manager.requestSingleUpdate(provider, legacy, Looper.getMainLooper());
            long limit = Math.max(1, Math.min(8_000, request.remainingMillis()));
            long until = SystemClock.elapsedRealtime() + limit;
            while (latch.getCount() != 0 && SystemClock.elapsedRealtime() < until) {
                if (request.isCancelled()) throw new WeatherFailure("LOCATION_CANCELLED");
                latch.await(Math.min(100, Math.max(1, until - SystemClock.elapsedRealtime())), TimeUnit.MILLISECONDS);
            }
            Location fix = result.get();
            if (fix == null) fix = manager.getLastKnownLocation(provider);
            if (fix == null) throw new WeatherFailure("LOCATION_UNAVAILABLE");
            long age = TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - fix.getElapsedRealtimeNanos());
            if (age < 0 || age > MAX_AGE_MILLIS) throw new WeatherFailure("LOCATION_STALE");
            if (!fix.hasAccuracy() || fix.getAccuracy() > MAX_ACCURACY_METERS) throw new WeatherFailure("LOCATION_INACCURATE");
            if (!Double.isFinite(fix.getLatitude()) || !Double.isFinite(fix.getLongitude())) throw new WeatherFailure("LOCATION_INVALID");
            return new Fix(fix.getLatitude(), fix.getLongitude(), fix.getAccuracy(), System.currentTimeMillis() - age);
        } catch (SecurityException denied) { throw new WeatherFailure("LOCATION_PERMISSION_DENIED"); }
        finally { signal.cancel(); manager.removeUpdates(legacy); request.getCancellationToken().removeAbortHook(abort); }
    }
}
