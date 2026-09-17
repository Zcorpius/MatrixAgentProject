package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * 外部唤醒事件(系统 VoiceInteractionService / OEM DSP 回调的归一化形态,§6.2)。
 *
 * <p>{@code elapsedRealtime} 用事件侧单调时钟(SystemClock.elapsedRealtime),供去重/冷却/乱序判定;
 * {@code audioZoneId} 可空——当前产品范围固定主驾默认音区,该字段仅作审计透传,
 * {@code VoiceEntryCoordinator} 不据其路由(§6.3-8:忽略外部声称的座位归属)。
 * {@code preRollHandle} 不落地(§6.3-5,OEM 路径到位再加)。
 */
public record WakeEvent(String source, String eventId, long elapsedRealtime, String audioZoneId) {
    public WakeEvent {
        if (source == null || source.isEmpty()) throw new IllegalArgumentException("source 不能为空");
        if (eventId == null || eventId.isEmpty()) throw new IllegalArgumentException("eventId 不能为空");
        if (elapsedRealtime < 0) throw new IllegalArgumentException("elapsedRealtime 不能为负");
    }
}
