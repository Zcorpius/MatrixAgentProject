package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.task.identity.InputSource;

/**
 * 语音会话发给 Agent 的窄调用请求。
 *
 * <p>替代旧 {@code AgentRunner.run(String, CancellationToken)} 的散乱参数,把转写结果、
 * 固定身份(主驾)、默认音区与 {@link InputSource#VOICE} 标记一并带给 Repository,
 * 让语音请求在 {@code AgentRequest} 里被准确识别为 VOICE。
 *
 * <p>{@code inputSource} 内部固定为 {@link InputSource#VOICE}——本类语义即"语音请求",
 * 不存在非 VOICE 构造,故不作为构造参数暴露。便捷构造器固定 {@link Actor#DRIVER} +
 * {@link #DEFAULT_DRIVER_ZONE}(Demo 范围);全参构造器供测试与多音区/副驾扩展。
 *
 * <p>{@code epoch}/{@code arbitrationKey}/{@code vehicleState} 等 Agent 内部字段不属本对象,
 * 由 {@code AgentRuntimeRepository} 在构造 {@code AgentRequest} 时注入。
 */
public final class VoiceAgentRequest {
    /** Demo 固定主驾驶音区。接 OEM 音区归属后由可信来源替换。 */
    public static final int DEFAULT_DRIVER_ZONE = 0;

    private final FinalTranscript transcript;
    private final Actor actor;
    private final int audioZoneId;
    private final CancellationToken cancellationToken;

    public VoiceAgentRequest(FinalTranscript transcript, CancellationToken cancellationToken) {
        this(transcript, Actor.DRIVER, DEFAULT_DRIVER_ZONE, cancellationToken);
    }

    public VoiceAgentRequest(FinalTranscript transcript, Actor actor, int audioZoneId,
            CancellationToken cancellationToken) {
        if (transcript == null) throw new IllegalArgumentException("transcript 不能为空");
        if (actor == null) throw new IllegalArgumentException("actor 不能为空");
        if (cancellationToken == null) throw new IllegalArgumentException("cancellationToken 不能为空");
        if (audioZoneId < 0) throw new IllegalArgumentException("audioZoneId 不能小于 0");
        this.transcript = transcript;
        this.actor = actor;
        this.audioZoneId = audioZoneId;
        this.cancellationToken = cancellationToken;
    }

    public FinalTranscript transcript() { return transcript; }
    public Actor actor() { return actor; }
    public int audioZoneId() { return audioZoneId; }
    /** 语音请求恒为 VOICE,不可构造参数。 */
    public InputSource inputSource() { return InputSource.VOICE; }
    public CancellationToken cancellationToken() { return cancellationToken; }
}
