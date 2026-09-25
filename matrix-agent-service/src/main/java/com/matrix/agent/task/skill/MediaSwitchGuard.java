package com.matrix.agent.task.skill;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.platform.media.MediaApp;
import com.matrix.agent.platform.media.ExplicitMediaTarget;
import com.matrix.agent.platform.media.MediaSwitchIntent;
import com.matrix.agent.task.capability.MediaCapabilities;
import com.matrix.agent.task.policy.PolicyDecision;
import com.matrix.agent.task.tool.ToolResult;

import java.util.EnumMap;
import java.util.Map;

/**
 * Request-local safety gate for a two-app handoff. The model may plan the workflow, but cannot
 * start target playback until the source pause has been read back as PAUSED. Missing
 * prerequisites are retriable parameter decisions; an unsafe operation remains blocked.
 */
public final class MediaSwitchGuard {
    private enum State { UNKNOWN, PLAYING, OTHER, ABSENT }
    private final boolean active;
    private final MediaApp requestedTarget;
    private final Map<MediaApp, State> states = new EnumMap<>(MediaApp.class);
    private MediaApp source;
    private boolean pauseConfirmed;
    private boolean targetDefinitelyNotSent;

    public MediaSwitchGuard(AgentRequest request) {
        String text = request.getText();
        active = MediaSwitchIntent.isRequested(text);
        requestedTarget = ExplicitMediaTarget.switchTarget(text);
        for (MediaApp app : MediaApp.values()) states.put(app, State.UNKNOWN);
    }

    public PolicyDecision before(ToolCall call) {
        if (!active || !MediaCapabilities.ALL.contains(call.getCapabilityName())) return null;
        if (isRead(call.getCapabilityName())) return null;
        if (requestedTarget == null) {
            return PolicyDecision.denyCapability("切换媒体来源需要明确指定目标应用");
        }
        if (source == null || states.get(requestedTarget) != State.OTHER) {
            return PolicyDecision.denyParameter("尚未确认唯一原来源和目标活动会话");
        }
        String capability = call.getCapabilityName();
        if (isOpen(capability)) {
            return PolicyDecision.denyCapability("切换时目标无可播放会话，请先手动选择内容");
        }
        MediaApp app = appOf(capability);
        if (isPause(capability)) {
            return app == source && !pauseConfirmed
                    ? null : PolicyDecision.denyCapability("只能暂停已确认的原来源");
        }
        if (isPlay(capability)) {
            if (app == requestedTarget) {
                return pauseConfirmed ? null
                        : PolicyDecision.denyParameter("原来源未确认暂停，禁止启动目标播放");
            }
            return app == source && targetDefinitelyNotSent && pauseConfirmed
                    ? null : PolicyDecision.denyParameter("目标播放结果未明确失败，禁止恢复原来源");
        }
        return PolicyDecision.denyCapability("切换流程不执行其它媒体写操作");
    }

    public void after(ToolCall call, ToolResult result) {
        if (!active || result == null) return;
        String capability = call.getCapabilityName();
        MediaApp app = appOf(capability);
        if (app == null) return;
        if (isRead(capability)) {
            if (result.getStatus() == ToolResult.Status.SUCCESS) {
                Object playback = result.getObservedState().get("media.playback_state");
                states.put(app, "PLAYING".equals(playback) ? State.PLAYING : State.OTHER);
            } else if ("NO_ACTIVE_SESSION".equals(result.getObservedState().get("media.error_code"))) {
                states.put(app, State.ABSENT);
            } else {
                states.put(app, State.UNKNOWN);
            }
            if (source == null && !pauseConfirmed) {
                source = states.get(MediaApp.QQMUSIC) == State.PLAYING
                        && states.get(MediaApp.BILIBILI) == State.OTHER
                        ? MediaApp.QQMUSIC
                        : states.get(MediaApp.BILIBILI) == State.PLAYING
                        && states.get(MediaApp.QQMUSIC) == State.OTHER
                        ? MediaApp.BILIBILI : null;
            }
            return;
        }
        if (isPause(capability) && app == source) {
            pauseConfirmed = result.getStatus() == ToolResult.Status.SUCCESS
                    && result.isVerified()
                    && "PAUSED".equals(result.getObservedState().get("media.playback_state"));
        }
        if (isPlay(capability) && app == requestedTarget) {
            targetDefinitelyNotSent = result.getStatus() == ToolResult.Status.EXECUTION_FAILED
                    && "NOT_SENT".equals(result.getObservedState().get("media.dispatch_state"));
        }
    }

    private static boolean isRead(String cap) {
        return MediaCapabilities.QQ_STATE.equals(cap) || MediaCapabilities.BILI_STATE.equals(cap);
    }
    private static boolean isOpen(String cap) {
        return MediaCapabilities.QQ_OPEN.equals(cap) || MediaCapabilities.BILI_OPEN.equals(cap);
    }
    private static boolean isPause(String cap) {
        return MediaCapabilities.QQ_PAUSE.equals(cap) || MediaCapabilities.BILI_PAUSE.equals(cap);
    }
    private static boolean isPlay(String cap) {
        return MediaCapabilities.QQ_PLAY.equals(cap) || MediaCapabilities.BILI_RESUME.equals(cap);
    }
    private static MediaApp appOf(String cap) {
        if (cap.startsWith("media.qqmusic.")) return MediaApp.QQMUSIC;
        if (cap.startsWith("media.bilibili.")) return MediaApp.BILIBILI;
        return null;
    }

}
