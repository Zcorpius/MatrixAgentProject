package com.matrix.agent.voice.port;

import com.matrix.agent.voice.*;

/**
 * 采音端口:统一采音会话生命周期与终止上报。
 *
 * <p>Demo 实现 {@code platform.voice.VoiceCaptureController}(AudioRecord+AEC+会话隔离);
 * JVM 测试注入假实现驱动 {@code VoiceRuntime} 恢复路径(pending 屏障/busy 重试/清理回调),
 * 量产 OEM 采音链路(DSP/无头服务)替换实现。
 *
 * <p><b>契约</b>:start 返回新会话 sid(≥1);busy(已有会话/STOPPING 残留)返回 -1;
 * 权限缺失抛 {@link SecurityException}。采音线程退出(正常或错误)经
 * {@link TerminationListener} 上送 sid+精确 reason(错误不直连 Controller,由 Runtime
 * 校验 sid 后转交);stop() 后迟到的终止回调携带旧 sid,由调用方丢弃。
 */
public interface VoiceCapturePort {
    /** 采音线程退出时通知(sid + reason);正常 stop=STOPPED,错误=精确 CAPTURE_* 码。 */
    interface TerminationListener {
        void onTerminated(long sessionId, String reason);
    }

    /** @return 新会话 sid(≥1);busy 返回 -1;权限缺失抛 SecurityException。 */
    long start();

    /** 停止采音并释放本会话资源(幂等)。 */
    void stop();

    void setTerminationListener(TerminationListener listener);
}
