package com.matrix.agent.voice.system;

import android.service.voice.VoiceInteractionService;
import android.service.voice.VoiceInteractionSession;
import android.os.Bundle;
import android.util.Log;

/**
 * 系统语音入口(批 C,debug manifest 声明;release 启用等发布开关批 E):设为默认助手后,
 * 系统侧触发(助手手势等)→ {@link #onGetSession} 会话 {@code onShow} → {@code SystemVisEntryAdapter}
 * → Coordinator → Runtime——不打开 MatrixAgent 自己的 UI、不按按钮(§6.6)。
 *
 * <p>仅是唤醒入口生命周期组件,不引入独立 Agent 进程/AIDL(§6.1)。身份丢失/服务死亡:
 * Service 销毁即入口消失,不回退后台软件常驻录音(§6.3-7;批 D 的 SESSION_ON_WAKE 保证)。
 */
public final class MatrixVoiceInteractionService extends VoiceInteractionService {
    private static final String TAG = "MatrixAgent";


    @Override
    public void onCreate() {
        super.onCreate();
        // owner 注入:系统唤醒冷启动时懒建 Runtime(follow-up1 P1)
        SystemVisEntryAdapter.setOwner(SystemVoiceRuntimeOwner.shared(getApplication()));
    }

    @Override
    public void onReady() {
        super.onReady();
        Log.i(TAG, "[Voice] VoiceInteractionService 就绪(当前默认语音助手)");
    }

    /** 默认助手被取消/服务关闭(follow-up2 P1):回收系统 Runtime,不留双采音。 */
    @Override
    public void onShutdown() {
        super.onShutdown();
        SystemVoiceRuntimeOwner.shared(getApplication()).shutdownIfOwned();
    }

    // 非标准厂商适配(真机验证后再定去留):标准 AOSP 会话模型只有 SessionService.onNewSession
    // (已注册);本方法针对华为 API 31 设备可能存在的私有回调保留的实验路径,不是通用兼容方案,
    // 主路径不依赖它。
    public VoiceInteractionSession onGetSession(Bundle args) {
        return new MatrixVoiceInteractionSessionService.WakeEntrySession(this);
    }
}
