package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * capabilityName → 中文友好播报名映射。
 *
 * <p>代码库目前没有 capability 的用户可读名({@code CapabilityDefinition} 只有 id 和自由文本
 * description,无 displayName)。{@link ResponsePresenter} 用本表把 capability id 翻译成
 * 口语短语;命中返回友好名,未命中返回兜底 {@link #FALLBACK}。
 *
 * <p>硬编码核心能力,后续可按需扩展或改为配置。前缀 {@code memory.preference.} 走通配:
 * 任何以该前缀开头的能力都映射为"记下你的偏好"。
 */
public final class CapabilitySpeechNames {
    /** 未命中映射时的兜底播报名。不说细节、不编造。 */
    public static final String FALLBACK = "这项操作";

    private static final String MEMORY_PREFERENCE_PREFIX = "memory.preference.";

    private static final Map<String, String> EXACT;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("vehicle.climate.set_temperature", "调整空调温度");
        m.put("vehicle.climate.set_fan_speed", "调整空调风量");
        m.put("vehicle.climate.toggle_ac", "切换空调");
        m.put("vehicle.seat.set_heating_level", "调整座椅加热");
        m.put("navigation.start_route", "开启导航");
        m.put("navigation.cancel_route", "取消导航");
        m.put("system.media.set_volume", "调整媒体音量");
        m.put("media.qqmusic.get_state", "查询 QQ 音乐播放状态");
        m.put("media.qqmusic.open_app", "打开 QQ 音乐");
        m.put("media.qqmusic.play", "播放 QQ 音乐");
        m.put("media.qqmusic.pause", "暂停 QQ 音乐");
        m.put("media.qqmusic.next", "播放 QQ 音乐下一首");
        m.put("media.qqmusic.previous", "播放 QQ 音乐上一首");
        m.put("media.qqmusic.seek", "调整 QQ 音乐播放位置");
        m.put("media.qqmusic.search_songs", "搜索 QQ 音乐歌曲");
        m.put("media.qqmusic.play_search_result", "播放选中的 QQ 音乐歌曲");
        m.put("media.bilibili.get_state", "查询哔哩哔哩播放状态");
        m.put("media.bilibili.open_video", "打开哔哩哔哩视频");
        m.put("media.bilibili.search_videos", "搜索哔哩哔哩视频");
        m.put("media.bilibili.open_search_result", "打开选中的哔哩哔哩视频");
        m.put("media.bilibili.pause", "暂停哔哩哔哩视频");
        m.put("media.bilibili.resume", "播放哔哩哔哩视频");
        m.put("system.display.set_brightness", "调整屏幕亮度");
        m.put("knowledge.answer", "回答问题");
        m.put("vehicle.info.get_battery", "查询电量");
        m.put("vehicle.info.get_tire_pressure", "查询胎压");
        EXACT = Collections.unmodifiableMap(m);
    }

    private CapabilitySpeechNames() {
    }

    /**
     * 返回 capabilityName 对应的中文友好播报名。
     *
     * @param capabilityName 能力 id,可为空(返回兜底)
     */
    public static String friendlyName(String capabilityName) {
        if (capabilityName == null || capabilityName.isEmpty()) return FALLBACK;
        String exact = EXACT.get(capabilityName);
        if (exact != null) return exact;
        if (capabilityName.startsWith(MEMORY_PREFERENCE_PREFIX)) return "记下你的偏好";
        return FALLBACK;
    }
}
