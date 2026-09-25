package com.matrix.agent.task.skill;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.RuntimeProfile;
import com.matrix.agent.task.capability.MediaCapabilities;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.policy.PolicyDecision;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.task.tool.ToolResult;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;

public final class MediaSwitchGuardTest {
    @Test public void targetPlaybackWaitsForVerifiedSourcePause() {
        MediaSwitchGuard guard = new MediaSwitchGuard(
                AgentRequest.builder("从QQ音乐切换到B站", Actor.DRIVER).build());
        ToolCall qqState = call(MediaCapabilities.QQ_STATE);
        ToolCall biliState = call(MediaCapabilities.BILI_STATE);
        ToolCall pause = call(MediaCapabilities.QQ_PAUSE);
        ToolCall resume = call(MediaCapabilities.BILI_RESUME);

        guard.after(qqState, result(qqState, ToolResult.Status.SUCCESS,
                false, Map.of("media.playback_state", "PLAYING")));
        guard.after(biliState, result(biliState, ToolResult.Status.SUCCESS,
                false, Map.of("media.playback_state", "PAUSED")));
        assertNull(guard.before(pause));
        assertNotNull(guard.before(resume));

        guard.after(pause, result(pause, ToolResult.Status.VERIFICATION_FAILED,
                false, Map.of("media.playback_state", "PLAYING")));
        assertNotNull(guard.before(resume));

        guard.after(pause, result(pause, ToolResult.Status.SUCCESS,
                true, Map.of("media.playback_state", "PAUSED")));
        assertNull(guard.before(resume));
    }

    @Test public void explicitTargetWinsEvenWhenSourceIsMentionedLater() {
        MediaSwitchGuard guard = new MediaSwitchGuard(
                AgentRequest.builder("切换到QQ音乐，从B站过来", Actor.DRIVER).build());
        ToolCall qqState = call(MediaCapabilities.QQ_STATE);
        ToolCall biliState = call(MediaCapabilities.BILI_STATE);
        ToolCall pause = call(MediaCapabilities.BILI_PAUSE);
        ToolCall play = call(MediaCapabilities.QQ_PLAY);

        guard.after(qqState, result(qqState, ToolResult.Status.SUCCESS,
                false, Map.of("media.playback_state", "PAUSED")));
        guard.after(biliState, result(biliState, ToolResult.Status.SUCCESS,
                false, Map.of("media.playback_state", "PLAYING")));
        assertNull(guard.before(pause));
        guard.after(pause, result(pause, ToolResult.Status.SUCCESS,
                true, Map.of("media.playback_state", "PAUSED")));
        assertNull(guard.before(play));
    }

    @Test public void ambiguousTargetBlocksWrites() {
        MediaSwitchGuard guard = new MediaSwitchGuard(
                AgentRequest.builder("切换 QQ 音乐与 B 站的媒体来源", Actor.DRIVER).build());
        assertNotNull(guard.before(call(MediaCapabilities.QQ_PLAY)));
    }

    @Test public void namelessSwitchIsHardGuardedButNegatedSwitchIsOrdinaryPause() {
        MediaSwitchGuard nameless = new MediaSwitchGuard(
                AgentRequest.builder("切换音乐来源", Actor.DRIVER).build());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY,
                nameless.before(call(MediaCapabilities.QQ_PLAY)).getRejectionType());
        MediaSwitchGuard negated = new MediaSwitchGuard(
                AgentRequest.builder("别切换，先暂停QQ音乐", Actor.DRIVER).build());
        assertNull(negated.before(call(MediaCapabilities.QQ_PAUSE)));
    }

    @Test public void singleNamedTargetCanPauseConfirmedOtherSourceAndThenPlay() {
        AgentRequest request = AgentRequest.builder("切换到QQ音乐", Actor.DRIVER).build();
        MediaSwitchGuard guard = new MediaSwitchGuard(request);
        PolicyEngine policy = new PolicyEngine(CapabilityRegistry.createRuntimeRegistry());
        ToolCall qqState = call(MediaCapabilities.QQ_STATE);
        ToolCall biliState = call(MediaCapabilities.BILI_STATE);
        ToolCall pause = call(MediaCapabilities.BILI_PAUSE);
        ToolCall play = call(MediaCapabilities.QQ_PLAY);

        assertEquals(PolicyDecision.RejectionType.PARAMETER,
                guard.before(play).getRejectionType());
        guard.after(qqState, result(qqState, ToolResult.Status.SUCCESS,
                false, Map.of("media.playback_state", "PAUSED")));
        guard.after(biliState, result(biliState, ToolResult.Status.SUCCESS,
                false, Map.of("media.playback_state", "PLAYING")));
        assertNull(guard.before(pause));
        assertTrue(policy.evaluate(request, pause).isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER,
                guard.before(play).getRejectionType());
        guard.after(pause, result(pause, ToolResult.Status.SUCCESS,
                true, Map.of("media.playback_state", "PAUSED")));
        assertNull(guard.before(play));
        assertTrue(policy.evaluate(request, play).isAllowed());
    }

    @Test public void imperativeHandoffWithoutSwitchVerbCanPauseSourceThenPlayTarget() {
        AgentRequest request = AgentRequest.builder(
                "暂停 QQ 音乐再播放 B 站", Actor.DRIVER)
                .runtimeProfile(RuntimeProfile.PHONE).build();
        MediaSwitchGuard guard = new MediaSwitchGuard(request);
        PolicyEngine policy = new PolicyEngine(CapabilityRegistry.createRuntimeRegistry());
        ToolCall qqState = call(MediaCapabilities.QQ_STATE);
        ToolCall biliState = call(MediaCapabilities.BILI_STATE);
        ToolCall pause = call(MediaCapabilities.QQ_PAUSE);
        ToolCall resume = call(MediaCapabilities.BILI_RESUME);

        assertEquals(PolicyDecision.RejectionType.PARAMETER,
                guard.before(resume).getRejectionType());
        guard.after(qqState, result(qqState, ToolResult.Status.SUCCESS,
                false, Map.of("media.playback_state", "PLAYING")));
        guard.after(biliState, result(biliState, ToolResult.Status.SUCCESS,
                false, Map.of("media.playback_state", "PAUSED")));
        assertNull(guard.before(pause));
        assertTrue(policy.evaluate(request, pause).isAllowed());
        assertEquals(PolicyDecision.RejectionType.PARAMETER,
                guard.before(resume).getRejectionType());
        guard.after(pause, result(pause, ToolResult.Status.SUCCESS,
                true, Map.of("media.playback_state", "PAUSED")));
        assertNull(guard.before(resume));
        assertTrue(policy.evaluate(request, resume).isAllowed());
    }

    @Test public void stateDescriptionDoesNotActivateHandoffGuard() {
        MediaSwitchGuard guard = new MediaSwitchGuard(
                AgentRequest.builder("B 站在播放，QQ音乐暂停了", Actor.DRIVER).build());
        assertNull(guard.before(call(MediaCapabilities.QQ_PAUSE)));
    }

    private static ToolCall call(String capability) {
        return new ToolCall(capability, Collections.emptyMap());
    }

    private static ToolResult result(ToolCall call, ToolResult.Status status,
            boolean verified, Map<String, Object> observed) {
        return new ToolResult(status, call.getCapabilityName(), "", observed, verified, 0L);
    }
}
