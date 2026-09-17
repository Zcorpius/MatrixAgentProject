package com.matrix.agent.voice.system;

import android.os.SystemClock;
import android.util.Log;

import com.matrix.agent.voice.VoiceEntryCoordinator;
import com.matrix.agent.voice.WakeEvent;
import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.VoiceRuntimeHolder;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 系统助手入口适配(批 C,§6.2 VoiceEntryAdapter 的 SYSTEM_VIS 实现):把
 * {@code MatrixVoiceInteractionService} 的系统回调归一化为 {@link WakeEvent},经
 * {@link VoiceEntryCoordinator} 闸门(来源/去重/冷却/单会话仲裁)后派发 Runtime。
 *
 * <p>Runtime 未注册(语音页未活跃/已销毁)时打日志丢弃——不拉起重资源(§6.3-6)。
 */
final class SystemVisEntryAdapter {
    private static volatile SystemVoiceRuntimeOwner owner;
    private static final String TAG = "MatrixAgent";

    /** 共享实例:Coordinator 去重/冷却状态跨会话保留(每会话新建会重置闸门)。 */
    private static final SystemVisEntryAdapter SHARED = new SystemVisEntryAdapter();

    static SystemVisEntryAdapter shared() { return SHARED; }

    /** owner 由 Service.onCreate 注入(生产装配);测试直接构造 owner。 */
    static void setOwner(SystemVoiceRuntimeOwner o) { owner = o; }

    private final VoiceEntryCoordinator coordinator;
    private final AtomicLong seq = new AtomicLong(); // 冷启动兜底序号(无 session 实例时)

    SystemVisEntryAdapter() {
        this.coordinator = new VoiceEntryCoordinator(new VoiceEntryCoordinator.Target() {
            @Override public void onWakeAccepted(WakeEvent event) {
                SystemVoiceRuntimeOwner o = owner;
                if (o == null) {
                    Log.i(TAG, "[Voice] 系统唤醒到达但 owner 未注入,丢弃");
                    return;
                }
                // follow-up2 P1:全部系统事件统一交 Owner(READY 派发/BUILDING 入槽),
                // 不再绕过 Owner 直呼 holder 里可能尚未装配 controller 的 Runtime
                o.onSystemWake(event);
            }

            @Override public boolean isSessionActive() {
                VoiceRuntime r = VoiceRuntimeHolder.get();
                return r != null && r.isSessionActive();
            }
        }, Set.of(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS));
    }

    /**
     * 系统侧触发(助手手势/会话回调)→ 归一化事件 → 闸门。
     * sessionId:WakeEntrySession 实例稳定 id(同一会话重复 onShow 命中 Coordinator 去重,
     * follow-up2 P2);API 34+ 可改 KEY_SHOW_SESSION_ID,列批 E。null 时冷启动序号兜底。
     */
    void onSystemTrigger(String trigger, String sessionId) {
        String eventId = sessionId != null ? sessionId : "sys-" + seq.incrementAndGet();
        WakeEvent event = new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS,
                eventId, SystemClock.elapsedRealtime(), null);
        boolean accepted = coordinator.onWakeEvent(event);
        Log.i(TAG, "[Voice] 系统助手触发(" + trigger + ") " + (accepted ? "已派发" : "被闸门丢弃"));
    }
}
