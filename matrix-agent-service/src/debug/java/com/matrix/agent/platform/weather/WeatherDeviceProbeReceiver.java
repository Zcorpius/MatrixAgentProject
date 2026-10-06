package com.matrix.agent.platform.weather;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import com.matrix.agent.data.schedule.ScheduleRunEntity;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.schedule.android.ScheduleNotificationPort;
import java.util.UUID;

/** Debug-only, credential-free checks on the target ROM. Never logs a fix or a secret. */
public final class WeatherDeviceProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "MatrixWeatherProbe";

    @Override public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        Context app = context.getApplicationContext();
        new Thread(() -> {
            try { run(app, intent); }
            catch (Exception failure) { Log.e(TAG, "probe failed: " + failure.getClass().getSimpleName()); }
            finally { pending.finish(); }
        }, "matrix-weather-probe").start();
    }

    private static void run(Context context, Intent command) throws Exception {
        boolean coarse = context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean background = context.checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean configured = new WeatherConfigStore(context).load() != null;
        Log.i(TAG, "environment coarse=" + coarse + " background=" + background + " configured=" + configured);
        if (command.getBooleanExtra("crypto", false)) {
            try {
                // Synthetic debug key only: it has no QWeather project access.
                String pem = "-----BEGIN PRIVATE KEY-----\n"
                        + "MC4CAQAwBQYDK2VwBCIEIL6VoMgy8offzF4g40LoM18mvENsLSzoshvY5hHCULl3\n"
                        + "-----END PRIVATE KEY-----";
                String jwt = QWeatherJwtSigner.sign("probe", "probe", "probe", pem, System.currentTimeMillis() / 1000);
                Log.i(TAG, "crypto signerAvailable=" + (jwt.split("\\.").length == 3));
            } catch (Exception failure) { Log.e(TAG, "crypto failed", failure); }
        }
        if (command.getBooleanExtra("location", false)) {
            long started = SystemClock.elapsedRealtime();
            try {
                var request = AgentRequest.builder("设备定位边界认证", Actor.DRIVER).timeoutMillis(9_000).build();
                LocationProviderPort.Fix fix = new AndroidCurrentLocationProvider(context).current(request);
                Log.i(TAG, "location result=AVAILABLE ageMs=" + Math.max(0, System.currentTimeMillis() - fix.fixedAtMillis())
                        + " accuracyBand=" + (fix.accuracyMeters() <= 1000 ? "<=1km" : "<=5km")
                        + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            } catch (WeatherFailure failure) {
                Log.i(TAG, "location result=" + failure.code() + " elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            }
        }
        if (command.getBooleanExtra("notification", false)) {
            String runId = UUID.randomUUID().toString();
            var run = new ScheduleRunEntity();
            run.runId = runId; run.scheduleId = "weather-device-probe";
            run.title = "天气通知链路测试"; run.scheduledAt = System.currentTimeMillis();
            var port = new ScheduleNotificationPort(context);
            String first = port.postWeatherPending(run);
            String second = port.postWeatherResult(run, "合成测试：同一条通知静默更新；未读取天气。");
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            String channel = "";
            if (manager != null) for (var item : manager.getActiveNotifications())
                if (("schedule:" + runId).equals(item.getTag())) channel = item.getNotification().getChannelId();
            Log.i(TAG, "notification pending=" + (first.isEmpty() ? "POSTED" : first)
                    + " update=" + (second.isEmpty() ? "POSTED" : second) + " finalChannel=" + channel);
            if (manager != null) manager.cancel("schedule:" + runId, 1);
        }
    }
}
