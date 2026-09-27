package com.matrix.agent.task.policy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.MediaCapabilities;

import org.junit.Test;

import java.util.Map;

public final class BilibiliVideoIntentPolicyTest {
    private final BilibiliVideoIntentPolicy policy = new BilibiliVideoIntentPolicy();

    @Test public void titleAloneCannotAuthorizeAnInventedBvNumber() {
        AgentRequest request = AgentRequest.builder(
                "播放哔哩哔哩的逃避可耻但是有用", Actor.DRIVER).build();
        assertNotNull(policy.evaluate(request, video("BV1xx411c7mD")));
    }

    @Test public void userProvidedBvNumberMayOpen() {
        AgentRequest request = AgentRequest.builder(
                "打开哔哩哔哩 https://www.bilibili.com/video/BV1xx411c7mD",
                Actor.DRIVER).build();
        assertNull(policy.evaluate(request, video("BV1xx411c7mD")));
    }

    private static ToolCall video(String bvid) {
        return new ToolCall(MediaCapabilities.BILI_OPEN, Map.of("bvid", bvid));
    }
}
