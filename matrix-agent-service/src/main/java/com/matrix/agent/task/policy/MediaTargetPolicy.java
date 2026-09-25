package com.matrix.agent.task.policy;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.platform.media.ExplicitMediaTarget;
import com.matrix.agent.platform.media.MediaApp;
import com.matrix.agent.platform.media.MediaSwitchIntent;
import com.matrix.agent.task.capability.MediaCapabilities;

/** Binds media writes to the named target, with a guarded source-pause handoff exception. */
public final class MediaTargetPolicy {
    public PolicyDecision evaluate(AgentRequest request, String capability) {
        MediaApp target = ExplicitMediaTarget.singleTarget(request.getText());
        if (target == null || !MediaCapabilities.ALL.contains(capability)
                || MediaCapabilities.QQ_STATE.equals(capability)
                || MediaCapabilities.BILI_STATE.equals(capability)) return null;
        // A source pause is part of an explicitly requested handoff. MediaSwitchGuard
        // separately proves which app is the source before allowing that pause.
        if (MediaSwitchIntent.isRequested(request.getText())
                && (MediaCapabilities.QQ_PAUSE.equals(capability)
                || MediaCapabilities.BILI_PAUSE.equals(capability))) return null;
        boolean qqCapability = capability.startsWith("media.qqmusic.");
        if (qqCapability == (target == MediaApp.QQMUSIC)) return null;
        return PolicyDecision.denyCapability("媒体操作的目标应用与用户明确指定的应用不一致");
    }
}
