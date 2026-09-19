package com.matrix.agent.platform.control;

import android.content.ContentResolver;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Log;

/** Android implementation of {@link SystemControlPort}; no audio/display state crosses Binder. */
public final class AndroidSystemControlAdapter implements SystemControlPort {
    private static final String TAG = "MatrixAgent";
    private static final int STREAM = AudioManager.STREAM_MUSIC;
    private static final int BRIGHTNESS_MIN = 1;
    private static final int BRIGHTNESS_MAX = 255;

    private final Context appContext;
    private final AudioManager audioManager;
    private final ContentResolver resolver;

    public AndroidSystemControlAdapter(Context context) {
        appContext = context.getApplicationContext();
        audioManager = appContext.getSystemService(AudioManager.class);
        resolver = appContext.getContentResolver();
    }

    @Override public ControlResult setMediaVolumePercent(int percent) {
        if (!inRange(percent, 0, 100)) return ControlResult.rejected(percent, "percent_out_of_range");
        if (audioManager == null) return ControlResult.rejected(percent, "audio_service_unavailable");
        try {
            int maximum = audioManager.getStreamMaxVolume(STREAM);
            if (maximum <= 0) return ControlResult.rejected(percent, "invalid_stream_range");
            int target = Math.round(maximum * percent / 100f);
            audioManager.setStreamVolume(STREAM, target, AudioManager.FLAG_SHOW_UI);
            int actual = audioManager.getStreamVolume(STREAM);
            int actualPercent = Math.round(actual * 100f / maximum);
            boolean verified = actual == target;
            Log.i(TAG, "[SystemControl] media volume requested=" + percent
                    + " actual=" + actualPercent + " verified=" + verified);
            return ControlResult.readback(percent, actualPercent, verified);
        } catch (SecurityException failure) {
            Log.w(TAG, "[SystemControl] media volume denied", failure);
            return ControlResult.rejected(percent, "audio_permission_denied");
        } catch (RuntimeException failure) {
            Log.w(TAG, "[SystemControl] media volume failed", failure);
            return ControlResult.rejected(percent, "audio_write_failed");
        }
    }

    @Override public ControlResult setScreenBrightnessPercent(int percent) {
        if (!inRange(percent, 1, 100)) return ControlResult.rejected(percent, "percent_out_of_range");
        if (!Settings.System.canWrite(appContext)) {
            return ControlResult.rejected(percent, "write_settings_not_granted");
        }
        try {
            // A manual user command must not be overwritten immediately by adaptive brightness.
            boolean modeSet = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            int targetRaw = percentToBrightness(percent);
            boolean brightnessSet = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS,
                    targetRaw);
            if (!modeSet || !brightnessSet) {
                return ControlResult.rejected(percent, "settings_provider_rejected_write");
            }
            int actualRaw = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS);
            int actualPercent = brightnessToPercent(actualRaw);
            boolean verified = actualRaw == targetRaw;
            Log.i(TAG, "[SystemControl] screen brightness requested=" + percent
                    + " actual=" + actualPercent + " verified=" + verified);
            return ControlResult.readback(percent, actualPercent, verified);
        } catch (Settings.SettingNotFoundException | SecurityException failure) {
            Log.w(TAG, "[SystemControl] screen brightness denied", failure);
            return ControlResult.rejected(percent, "brightness_permission_denied");
        } catch (RuntimeException failure) {
            Log.w(TAG, "[SystemControl] screen brightness failed", failure);
            return ControlResult.rejected(percent, "brightness_write_failed");
        }
    }

    static int percentToBrightness(int percent) {
        return Math.round(BRIGHTNESS_MIN + (BRIGHTNESS_MAX - BRIGHTNESS_MIN) * percent / 100f);
    }

    static int brightnessToPercent(int rawBrightness) {
        int bounded = Math.max(BRIGHTNESS_MIN, Math.min(BRIGHTNESS_MAX, rawBrightness));
        return Math.round((bounded - BRIGHTNESS_MIN) * 100f
                / (BRIGHTNESS_MAX - BRIGHTNESS_MIN));
    }

    private static boolean inRange(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }
}
