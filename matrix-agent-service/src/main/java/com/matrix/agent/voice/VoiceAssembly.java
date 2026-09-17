package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * 一次装配产物(端口集合的收拢句柄):Runtime 只需 controller/capture 与整体回收,
 * 不感知引擎内部(holder/engine/native 端口等由实现持有)。
 *
 * <p>{@link #close()} 完成 native 生命周期回收:Controller shutdown(串行 stop asr/wake/tts
 * → afterStopped)后释放引擎资源(Recognizer 必须先于 Model close,顺序由实现保证)。
 */
public interface VoiceAssembly extends AutoCloseable {

    /** 会话控制器(已注入全部端口与 runner)。 */
    com.matrix.agent.voice.VoiceSessionController controller();

    /** 采音端口(会话隔离/sid/精确终止原因,见 {@link VoiceCapturePort})。 */
    VoiceCapturePort capture();

    /** 整体回收(幂等);调用后装配不可再用。 */
    @Override
    void close();
}
