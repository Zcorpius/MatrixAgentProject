package com.matrix.agent.voice;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ASR 引擎选择偏好（阶段 D-4）。
 *
 * <p>存储在 Host 私有 SharedPreferences；VoiceRuntime 装配时读取决定用哪个工厂。
 * 默认 VOSK——用户在语音页显式切换到 SHERPA 后才走新引擎。</p>
 */
public final class VoiceEnginePreference {

    private static final String PREF_FILE = "voice_engine_pref";
    private static final String KEY_ENGINE = "asr_engine";

    private final SharedPreferences preferences;

    public VoiceEnginePreference(Context context) {
        this.preferences = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    /** 当前引擎选择；默认 VOSK。 */
    public AsrEngineSelection getEngine() {
        String name = preferences.getString(KEY_ENGINE, AsrEngineSelection.VOSK.name());
        try {
            return AsrEngineSelection.valueOf(name);
        } catch (IllegalArgumentException invalid) {
            return AsrEngineSelection.VOSK;
        }
    }

    /** 切换引擎（语音页设置调用）；下次 VoiceRuntime 装配生效。 */
    public void setEngine(AsrEngineSelection engine) {
        if (engine == null) engine = AsrEngineSelection.VOSK;
        preferences.edit().putString(KEY_ENGINE, engine.name()).apply();
    }

    public boolean isSherpaSelected() {
        return getEngine() == AsrEngineSelection.SHERPA;
    }
}
