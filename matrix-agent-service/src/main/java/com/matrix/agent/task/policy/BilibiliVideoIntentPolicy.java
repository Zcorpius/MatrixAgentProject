package com.matrix.agent.task.policy;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.MediaCapabilities;

import java.util.Locale;

/** A video title cannot authorize a model-invented BV identifier. */
public final class BilibiliVideoIntentPolicy {
    public PolicyDecision evaluate(AgentRequest request, ToolCall call) {
        if (!MediaCapabilities.BILI_OPEN.equals(call.getCapabilityName())) return null;
        Object raw = call.argument("bvid");
        if (!(raw instanceof String) || !request.getText().toLowerCase(Locale.ROOT)
                .contains(((String) raw).toLowerCase(Locale.ROOT))) {
            return PolicyDecision.denyCapability(
                    "打开哔哩哔哩视频需要用户在本轮提供对应 BV 号或视频链接");
        }
        return null;
    }
}
