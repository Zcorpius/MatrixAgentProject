package com.matrix.agent.task.skill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.platform.media.PendingMediaConfirmation;
import com.matrix.agent.platform.media.QQMusicUiPort;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.task.capability.MediaCapabilities;

import org.junit.Test;

import java.util.List;
import java.util.Optional;

public final class QQMusicWorkflowGatewayTest {
    @Test public void exactSongSearchAndConfirmationDoNotCallRemoteModel() {
        FakePending pending = new FakePending();
        ModelGateway remote = request -> { throw new AssertionError("remote model must not run"); };
        QQMusicWorkflowGateway gateway = new QQMusicWorkflowGateway(remote, pending);

        ModelTurn search = gateway.decide(turn("播放李健的传奇",
                List.of(AgentMessage.user("播放李健的传奇"))));
        assertEquals(MediaCapabilities.QQ_SEARCH,
                search.getToolCalls().get(0).getCapabilityName());
        assertEquals("李健 传奇", search.getToolCalls().get(0).argument("query"));

        pending.present = true;
        ToolCall searchCall = search.getToolCalls().get(0);
        ModelTurn proposal = gateway.decide(turn("播放李健的传奇", List.of(
                AgentMessage.user("播放李健的传奇"), search.getAssistantMessage(),
                AgentMessage.tool(searchCall.getStepId(), MediaCapabilities.QQ_SEARCH,
                        "SUCCESS: found observed={media.confirmable_index=1}"))));
        assertFalse(proposal.hasToolCalls());
        assertTrue(proposal.getAssistantMessage().getContent().contains("是否播放这首"));

        ModelTurn play = gateway.decide(turn("是", List.of(AgentMessage.user("是"))));
        assertEquals(MediaCapabilities.QQ_PLAY_RESULT,
                play.getToolCalls().get(0).getCapabilityName());
        assertEquals(1, play.getToolCalls().get(0).argument("index"));

        ToolCall playCall = play.getToolCalls().get(0);
        ModelTurn done = gateway.decide(turn("是", List.of(AgentMessage.user("是"),
                play.getAssistantMessage(), AgentMessage.tool(playCall.getStepId(),
                        MediaCapabilities.QQ_PLAY_RESULT,
                        "SUCCESS: verified observed={media.playback_state=PLAYING}"))));
        assertFalse(done.hasToolCalls());
        assertTrue(done.getAssistantMessage().getContent().contains("已确认开始播放"));
    }

    @Test public void repeatedRequestStillAsksAndNegativeReplyDiscards() {
        FakePending pending = new FakePending();
        pending.present = true;
        QQMusicWorkflowGateway gateway = new QQMusicWorkflowGateway(
                request -> { throw new AssertionError("remote model must not run"); }, pending);
        ModelTurn repeat = gateway.decide(turn("播放李健的传奇",
                List.of(AgentMessage.user("播放李健的传奇"))));
        assertFalse(repeat.hasToolCalls());
        assertTrue(repeat.getAssistantMessage().getContent().contains("是否播放"));

        ModelTurn no = gateway.decide(turn("不要播放",
                List.of(AgentMessage.user("不要播放"))));
        assertFalse(no.hasToolCalls());
        assertTrue(pending.discarded);
    }

    @Test public void artistOnlyRequestSearchesWithoutChoosingOrPlaying() {
        QQMusicWorkflowGateway gateway = new QQMusicWorkflowGateway(
                request -> { throw new AssertionError("remote model must not run"); },
                new FakePending());
        ModelTurn search = gateway.decide(turn("播放周深的歌",
                List.of(AgentMessage.user("播放周深的歌"))));
        assertEquals(1, search.getToolCalls().size());
        assertEquals(MediaCapabilities.QQ_SEARCH,
                search.getToolCalls().get(0).getCapabilityName());
        assertEquals("周深", search.getToolCalls().get(0).argument("query"));
    }

    @Test public void ambiguousResultsRequireAnExplicitCandidate() {
        FakePending pending = new FakePending();
        pending.present = true;
        pending.confirmableIndex = 0;
        pending.originalRequest = "播放周深的歌";
        pending.candidates = List.of(
                new QQMusicUiPort.Candidate(1, "大鱼", "周深·大鱼"),
                new QQMusicUiPort.Candidate(2, "光亮", "周深·反深代词"));
        QQMusicWorkflowGateway gateway = new QQMusicWorkflowGateway(
                request -> { throw new AssertionError("remote model must not run"); }, pending);

        ModelTurn vague = gateway.decide(turn("是", List.of(AgentMessage.user("是"))));
        assertFalse(vague.hasToolCalls());
        assertTrue(vague.getAssistantMessage().getContent().contains("指定歌名或序号"));

        ModelTurn repeated = gateway.decide(turn("播放周深的歌",
                List.of(AgentMessage.user("播放周深的歌"))));
        assertFalse(repeated.hasToolCalls());
        assertTrue(repeated.getAssistantMessage().getContent().contains("《光亮》"));

        ModelTurn selected = gateway.decide(turn("第 2 首",
                List.of(AgentMessage.user("第 2 首"))));
        assertEquals(MediaCapabilities.QQ_PLAY_RESULT,
                selected.getToolCalls().get(0).getCapabilityName());
        assertEquals(2, selected.getToolCalls().get(0).argument("index"));
    }

    @Test public void explicitBilibiliRequestNeverBecomesAQQMusicSongSearch() {
        FakePending pending = new FakePending();
        pending.present = true;
        String request = "播放哔哩哔哩的逃避可耻但是有用";
        QQMusicWorkflowGateway gateway = new QQMusicWorkflowGateway(
                turn -> ModelTurn.directAnswer("交给哔哩哔哩流程"), pending);

        ModelTurn result = gateway.prepare(turn(request,
                List.of(AgentMessage.user(request)))).call();
        assertFalse(result.hasToolCalls());
        assertEquals("交给哔哩哔哩流程", result.getAssistantMessage().getContent());
        assertTrue(pending.discarded);
    }

    private static ModelTurnRequest turn(String text, List<AgentMessage> conversation) {
        AgentRequest request = AgentRequest.builder(text, Actor.DRIVER)
                .sessionId("music-session").build();
        return new ModelTurnRequest(request, conversation, List.of(), "system",
                new SessionContext());
    }

    private static final class FakePending implements PendingMediaConfirmation {
        boolean present;
        boolean discarded;
        int confirmableIndex = 1;
        String originalRequest = "播放李健的传奇";
        List<QQMusicUiPort.Candidate> candidates = List.of(
                new QQMusicUiPort.Candidate(1, "传奇", "李健·似水流年"));
        @Override public boolean hasPendingConfirmation(String sessionId) {
            return present && !discarded;
        }
        @Override public Optional<Snapshot> snapshot(String sessionId) {
            return !present || discarded ? Optional.empty() : Optional.of(new Snapshot(
                    candidates, confirmableIndex, originalRequest));
        }
        @Override public void discard(String sessionId) { discarded = true; }
    }
}
