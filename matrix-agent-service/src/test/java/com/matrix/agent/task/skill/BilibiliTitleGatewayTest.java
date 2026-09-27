package com.matrix.agent.task.skill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.platform.media.BilibiliUiPort;
import com.matrix.agent.platform.media.PendingBilibiliSelection;

import org.junit.Test;

import java.util.List;

public final class BilibiliTitleGatewayTest {
    private final PendingBilibiliSelection.Snapshot candidates =
            new PendingBilibiliSelection.Snapshot(List.of(
                    new BilibiliUiPort.Candidate(1, "逃避虽可耻但有用 特别篇", "番剧/影视", "相关作品"),
                    new BilibiliUiPort.Candidate(2, "逃避虽可耻但有用", "番剧/影视", "相关作品")),
                    "播放哔哩哔哩的逃避可耻但是有用");
    private final BilibiliTitleGateway gateway = new BilibiliTitleGateway(
            request -> { throw new AssertionError("remote model must not run"); },
            new com.matrix.agent.platform.media.PendingBilibiliSelection() {
                @Override public java.util.Optional<Snapshot> bilibiliSnapshot(String sessionId) {
                    return "bili-session".equals(sessionId)
                            ? java.util.Optional.of(candidates) : java.util.Optional.empty();
                }
                @Override public void discardBilibili(String sessionId) {}
            });

    @Test public void titleOnlyRequestSearchesBilibiliWithoutOpeningQQMusic() {
        ModelTurn result = gateway.prepare(turn(
                "播放哔哩哔哩的逃避可耻但是有用", "fresh-session")).call();
        assertTrue(result.hasToolCalls());
        assertTrue(result.getToolCalls().get(0).getCapabilityName().contains("bilibili.search_videos"));
    }

    @Test public void successfulSearchListsCandidatesWithoutOpeningOne() {
        ModelTurn result = gateway.decide(turnWithTool("播放哔哩哔哩的逃避可耻但是有用",
                "media.bilibili.search_videos", "SUCCESS: candidates found"));
        assertTrue(!result.hasToolCalls());
        assertTrue(result.getAssistantMessage().getContent().contains("1. 《逃避虽可耻但有用 特别篇》"));
        assertTrue(result.getAssistantMessage().getContent().contains("2. 《逃避虽可耻但有用》"));
    }

    @Test public void affirmativeWithoutChoiceDoesNotOpenAnAmbiguousResult() {
        ModelTurn result = gateway.decide(turn("是"));
        assertTrue(!result.hasToolCalls());
        assertTrue(result.getAssistantMessage().getContent().contains("请回复序号"));
    }

    @Test public void explicitIndexOpensOnlyThatResult() {
        ModelTurn result = gateway.decide(turn("第2个"));
        assertEquals("media.bilibili.open_search_result",
                result.getToolCalls().get(0).getCapabilityName());
        assertEquals(2, result.getToolCalls().get(0).argument("index"));
    }

    private static ModelTurnRequest turn(String text) {
        return turn(text, "bili-session");
    }

    private static ModelTurnRequest turn(String text, String sessionId) {
        return new ModelTurnRequest(AgentRequest.builder(text, Actor.DRIVER)
                .sessionId(sessionId).build(),
                List.of(AgentMessage.user(text)), List.of(), "system", new SessionContext());
    }

    private static ModelTurnRequest turnWithTool(String text, String tool, String observation) {
        return new ModelTurnRequest(AgentRequest.builder(text, Actor.DRIVER)
                .sessionId("bili-session").build(),
                List.of(AgentMessage.user(text), AgentMessage.tool("test-call", tool, observation)),
                List.of(), "system", new SessionContext());
    }
}
