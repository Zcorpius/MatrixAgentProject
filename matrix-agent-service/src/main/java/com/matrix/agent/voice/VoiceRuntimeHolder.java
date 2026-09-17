package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * 应用级 VoiceRuntime 持有者(阶段 3 批 C:§4「系统入口不依赖 Fragment 已创建」的最终形态)。
 *
 * <p>当前消费者是系统语音入口
 * {@code MatrixVoiceInteractionService}(系统助手手势 → Coordinator → 本 holder 取 Runtime)。
 * holder 不创建 Runtime(所有权仍在注册方),未注册时 {@link #get()} 返回 null——
 * 系统入口对 null 打日志丢弃,不拉起重资源(§6.3-6 懒加载边界)。
 */
public final class VoiceRuntimeHolder {
    private static volatile VoiceRuntime runtime;

    private VoiceRuntimeHolder() { }

    /** 注册/更新当前 Runtime（Host 或系统入口活跃期持有）。 */
    public static void set(VoiceRuntime r) {
        runtime = r;
    }

    /** 仅当仍是自己注册的实例时清空(防清掉后注册的新 Runtime)。 */
    public static void clearIfSame(VoiceRuntime r) {
        if (r != null && runtime == r) runtime = null;
    }

    /** @return 当前注册的 Runtime;null=语音未就绪(调用方丢弃事件,不拉起重资源)。 */
    public static VoiceRuntime get() {
        return runtime;
    }
}
