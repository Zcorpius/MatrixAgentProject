package com.matrix.agent.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ApiProtocol;
import com.matrix.agent.contract.LlmClient;
import com.matrix.agent.contract.ModelConfig;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.session.SessionManager;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public final class LlmPlannerCompatibilityLoopTest {
    @Test public void nextTurnReceivesSkillAndObservationThenChoosesOneAction() {
        CapturingClient client = new CapturingClient();
        LlmPlanner planner = new LlmPlanner(client, new ModelConfig("test", "test",
                ApiProtocol.OPENAI_CHAT, "http://localhost/v1", "test", "", false));
        ToolCall prior = new ToolCall("media.qqmusic.get_state", Map.of());
        AgentRequest request = AgentRequest.builder("暂停 QQ 音乐", Actor.DRIVER).build();
        ModelTurn turn = planner.decide(new ModelTurnRequest(request, List.of(
                AgentMessage.user("暂停 QQ 音乐"),
                AgentMessage.assistant("先查状态", List.of(prior)),
                AgentMessage.tool(prior.getStepId(), prior.getCapabilityName(),
                        "SUCCESS: playback=PLAYING observed={media.playback_state=PLAYING}")),
                List.of(), "<skill_guidance>先确认状态再暂停</skill_guidance>",
                new SessionManager().getOrCreate("compatibility-test")));

        assertTrue(client.system.contains("<skill_guidance>先确认状态再暂停</skill_guidance>"));
        assertTrue(client.user.contains("media.qqmusic.get_state"));
        assertTrue(client.user.contains("media.playback_state=PLAYING"));
        assertEquals(request.getCancellationToken(), client.token);
        assertEquals(request.getDeadlineAtMillis(), client.deadline);
        assertTrue(turn.hasToolCalls());
        assertEquals(1, turn.getToolCalls().size());
        assertEquals("media.qqmusic.pause", turn.getToolCalls().get(0).getCapabilityName());
    }

    @Test public void emptyStepsEndsTheLoop() {
        CapturingClient client = new CapturingClient();
        client.response = "{\"summary\":\"无法按歌手选曲\",\"steps\":[]}";
        LlmPlanner planner = new LlmPlanner(client, new ModelConfig("test", "test",
                ApiProtocol.OPENAI_CHAT, "http://localhost/v1", "test", "", false));
        AgentRequest request = AgentRequest.builder("播放李健的歌", Actor.DRIVER).build();
        ModelTurn turn = planner.decide(new ModelTurnRequest(request,
                List.of(AgentMessage.user("播放李健的歌")), List.of(), "规则",
                new SessionManager().getOrCreate("compatibility-test")));
        assertFalse(turn.hasToolCalls());
        assertTrue(turn.getAssistantMessage().getContent().contains("无法按歌手选曲"));
    }

    private static final class CapturingClient implements LlmClient {
        String system;
        String user;
        CancellationToken token;
        long deadline;
        String response = "{\"summary\":\"暂停\",\"steps\":["
                + "{\"capability\":\"media.qqmusic.pause\",\"arguments\":{}},"
                + "{\"capability\":\"media.qqmusic.play\",\"arguments\":{}}]}";

        @Override public String complete(ModelConfig config, String systemPrompt,
                String userPrompt) {
            system = systemPrompt;
            user = userPrompt;
            return response;
        }

        @Override public String complete(ModelConfig config, String systemPrompt,
                String userPrompt, CancellationToken token, long deadlineAtMillis) {
            this.token = token;
            this.deadline = deadlineAtMillis;
            return complete(config, systemPrompt, userPrompt);
        }
    }
}
