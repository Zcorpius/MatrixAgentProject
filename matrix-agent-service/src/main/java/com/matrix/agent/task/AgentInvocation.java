package com.matrix.agent.task;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.task.identity.InputSource;

/**
 * Host-adapted input to the task runtime.
 *
 * <p>This is deliberately a task contract, not a voice DTO.  An adapter such as the voice
 * runtime may preserve ASR metadata here, but task never needs to know which capture pipeline
 * produced it.  Session/arbitration identity, policy hints and the data epoch remain task-owned
 * derivations in {@link AgentRuntimeRepository}.</p>
 */
public final class AgentInvocation {
    private final String text;
    private final Actor actor;
    private final int audioZoneId;
    private final InputSource inputSource;
    private final String languageTag;
    private final float confidence;
    private final boolean confidenceAvailable;
    private final CancellationToken cancellationToken;

    public AgentInvocation(String text, Actor actor, int audioZoneId, InputSource inputSource,
            String languageTag, float confidence, boolean confidenceAvailable,
            CancellationToken cancellationToken) {
        if (actor == null) throw new IllegalArgumentException("actor 不能为空");
        if (audioZoneId < 0) throw new IllegalArgumentException("audioZoneId 不能小于 0");
        if (inputSource == null) throw new IllegalArgumentException("inputSource 不能为空");
        if (languageTag == null || languageTag.trim().isEmpty()) {
            throw new IllegalArgumentException("languageTag 不能为空");
        }
        if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("confidence 必须在 0 到 1 之间");
        }
        if (cancellationToken == null) {
            throw new IllegalArgumentException("cancellationToken 不能为空");
        }
        this.text = text == null ? "" : text;
        this.actor = actor;
        this.audioZoneId = audioZoneId;
        this.inputSource = inputSource;
        this.languageTag = languageTag;
        this.confidence = confidence;
        this.confidenceAvailable = confidenceAvailable;
        this.cancellationToken = cancellationToken;
    }

    public String text() { return text; }
    public Actor actor() { return actor; }
    public int audioZoneId() { return audioZoneId; }
    public InputSource inputSource() { return inputSource; }
    public String languageTag() { return languageTag; }
    public float confidence() { return confidence; }
    public boolean confidenceAvailable() { return confidenceAvailable; }
    public CancellationToken cancellationToken() { return cancellationToken; }
}
