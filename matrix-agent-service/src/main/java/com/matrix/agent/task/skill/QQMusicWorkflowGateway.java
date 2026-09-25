package com.matrix.agent.task.skill;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.CancellableModelCall;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.platform.media.MediaSelectionUtterance;
import com.matrix.agent.platform.media.ExplicitMediaTarget;
import com.matrix.agent.platform.media.MediaApp;
import com.matrix.agent.platform.media.PendingMediaConfirmation;
import com.matrix.agent.platform.media.QQMusicUiPort;
import com.matrix.agent.task.capability.MediaCapabilities;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes the narrow search → confirmation workflow without a remote model round trip.
 * The ordinary AgentEngine still owns policy, Tool execution, readback, audit and budgets.
 * All other requests retain the configured model gateway.
 */
public final class QQMusicWorkflowGateway implements ModelGateway {
    private static final Pattern EXACT_SONG = Pattern.compile(
            "^\\s*(?:请|帮我)?\\s*(?:播放|放|听)\\s*"
                    + "([\\p{IsHan}A-Za-z0-9]{2,24})的(.{2,48}?)\\s*[。！!]?\\s*$");
    private static final Pattern ARTIST_SONGS = Pattern.compile(
            "^\\s*(?:请|帮我)?\\s*(?:播放|放|听)\\s*"
                    + "([\\p{IsHan}A-Za-z0-9]{2,24})的(?:歌|歌曲)\\s*[。！!]?\\s*$");

    private final ModelGateway delegate;
    private final PendingMediaConfirmation pending;

    public QQMusicWorkflowGateway(ModelGateway delegate, PendingMediaConfirmation pending) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.pending = Objects.requireNonNull(pending, "pending");
    }

    @Override public ExecutionLane executionLane() { return delegate.executionLane(); }

    @Override public CancellableModelCall prepare(ModelTurnRequest request) {
        if (ExplicitMediaTarget.singleTarget(request.getAgentRequest().getText())
                == MediaApp.BILIBILI) {
            pending.discard(request.getAgentRequest().getSessionId());
            return delegate.prepare(request);
        }
        return handles(request) ? ModelGateway.super.prepare(request) : delegate.prepare(request);
    }

    @Override public ModelTurn decide(ModelTurnRequest request) {
        if (ExplicitMediaTarget.singleTarget(request.getAgentRequest().getText())
                == MediaApp.BILIBILI) {
            pending.discard(request.getAgentRequest().getSessionId());
            return delegate.decide(request);
        }
        AgentMessage last = lastMessage(request.getConversation());
        if (last != null && last.getRole() == AgentMessage.Role.TOOL) {
            if (MediaCapabilities.QQ_SEARCH.equals(last.getToolName())) {
                return afterSearch(request, last);
            }
            if (MediaCapabilities.QQ_PLAY_RESULT.equals(last.getToolName())) {
                return afterPlay(last);
            }
        }

        String text = request.getAgentRequest().getText();
        String sessionId = request.getAgentRequest().getSessionId();
        Optional<PendingMediaConfirmation.Snapshot> snapshot = pending.snapshot(sessionId);
        if (snapshot.isPresent()) {
            if (MediaSelectionUtterance.isNegative(text)) {
                pending.discard(sessionId);
                return ModelTurn.directAnswer("好的，不播放这首歌曲。");
            }
            if (text.strip().equals(snapshot.get().originalRequestText().strip())) {
                return snapshot.get().confirmableIndex() > 0
                        ? ModelTurn.directAnswer(confirmationQuestion(snapshot.get()))
                        : ModelTurn.directAnswer(candidateList(snapshot.get()));
            }
            int choice = snapshot.get().confirmableIndex() > 0
                    && MediaSelectionUtterance.isAffirmative(text)
                    ? snapshot.get().confirmableIndex()
                    : selectedCandidate(text, snapshot.get().candidates());
            if (choice > 0) {
                return ModelTurn.ofToolCalls(List.of(new ToolCall(
                        MediaCapabilities.QQ_PLAY_RESULT, Map.of("index", choice))),
                        "正在播放已确认的 QQ 音乐搜索结果");
            }
            if (snapshot.get().confirmableIndex() == 0
                    && MediaSelectionUtterance.isAffirmative(text)) {
                return ModelTurn.directAnswer("请先指定歌名或序号，暂未播放新歌曲。");
            }
        }
        if (snapshot.isEmpty() && MediaSelectionUtterance.isConfirmationReply(text)
                && conversationAskedForPlayback(request.getConversation())) {
            return ModelTurn.directAnswer("上次搜索结果已过期，请重新说出歌手和歌名。没有播放歌曲。");
        }

        String artist = artistSongs(text);
        if (artist != null) {
            return ModelTurn.ofToolCalls(List.of(new ToolCall(MediaCapabilities.QQ_SEARCH,
                    Map.of("query", artist))), "正在搜索该歌手的歌曲");
        }
        SongRequest song = exactSong(text);
        if (song != null) {
            return ModelTurn.ofToolCalls(List.of(new ToolCall(MediaCapabilities.QQ_SEARCH,
                    Map.of("query", song.artist() + " " + song.title()))),
                    "正在搜索指定歌手和歌曲");
        }
        return delegate.decide(request);
    }

    private boolean handles(ModelTurnRequest request) {
        if (ExplicitMediaTarget.singleTarget(request.getAgentRequest().getText())
                == MediaApp.BILIBILI) return false;
        AgentMessage last = lastMessage(request.getConversation());
        if (last != null && last.getRole() == AgentMessage.Role.TOOL
                && (MediaCapabilities.QQ_SEARCH.equals(last.getToolName())
                || MediaCapabilities.QQ_PLAY_RESULT.equals(last.getToolName()))) return true;
        String text = request.getAgentRequest().getText();
        if (artistSongs(text) != null || exactSong(text) != null) return true;
        Optional<PendingMediaConfirmation.Snapshot> snapshot = pending.snapshot(
                request.getAgentRequest().getSessionId());
        if (snapshot.isEmpty() && MediaSelectionUtterance.isConfirmationReply(text)
                && conversationAskedForPlayback(request.getConversation())) return true;
        return snapshot.isPresent() && (MediaSelectionUtterance.isConfirmationReply(text)
                || selectedCandidate(text, snapshot.get().candidates()) > 0
                || text.strip().equals(snapshot.get().originalRequestText().strip()));
    }

    private ModelTurn afterSearch(ModelTurnRequest request, AgentMessage observation) {
        if (!observation.getContent().startsWith("SUCCESS:")) {
            return ModelTurn.directAnswer(observation.getContent().contains("SEARCH_NO_RESULTS")
                    ? "QQ 音乐没有找到匹配歌曲，请换个歌名或歌手再试。"
                    : observation.getContent().contains("SEARCH_UI_CHANGED")
                    ? "QQ 音乐当前页面无法进入歌曲搜索，请回到 QQ 音乐首页后重试。没有播放新歌曲。"
                    : "QQ 音乐搜索未成功，请稍后重试。没有播放歌曲。");
        }
        Optional<PendingMediaConfirmation.Snapshot> snapshot = pending.snapshot(
                request.getAgentRequest().getSessionId());
        if (snapshot.isEmpty()) {
            return ModelTurn.directAnswer("搜索结果已过期，请重新搜索。没有播放歌曲。");
        }
        if (snapshot.get().confirmableIndex() > 0) {
            return ModelTurn.directAnswer(confirmationQuestion(snapshot.get()));
        }
        return ModelTurn.directAnswer(candidateList(snapshot.get()));
    }

    private static String candidateList(PendingMediaConfirmation.Snapshot snapshot) {
        StringBuilder answer = new StringBuilder("找到以下歌曲，请回复歌名或序号后再播放：");
        for (QQMusicUiPort.Candidate candidate : snapshot.candidates()) {
            answer.append('\n').append(candidate.index()).append(". 《")
                    .append(candidate.title()).append("》— ").append(candidate.detail());
        }
        return answer.toString();
    }

    private static ModelTurn afterPlay(AgentMessage observation) {
        String content = observation.getContent();
        if (content.startsWith("SUCCESS:")) {
            return ModelTurn.directAnswer("已确认开始播放所选歌曲。");
        }
        if (content.contains("SEARCH_CONTEXT_EXPIRED")) {
            return ModelTurn.directAnswer("搜索结果已过期，请重新提出歌曲请求。没有重新点击播放。");
        }
        if (content.contains("SELECTION_NOT_CONFIRMED")) {
            return ModelTurn.directAnswer("选曲尚未得到确认，没有播放歌曲。");
        }
        if (content.startsWith("VERIFICATION_FAILED:")
                || content.startsWith("EXECUTION_UNKNOWN:")) {
            return ModelTurn.directAnswer("已提交选曲，但未能确认是否开始播放；请查看 QQ 音乐当前状态。");
        }
        return ModelTurn.directAnswer("QQ 音乐未能播放所选歌曲，请重新搜索。没有自动重试。");
    }

    private static String confirmationQuestion(PendingMediaConfirmation.Snapshot snapshot) {
        QQMusicUiPort.Candidate candidate = snapshot.candidates()
                .get(snapshot.confirmableIndex() - 1);
        return "找到第 " + candidate.index() + " 首《" + candidate.title() + "》— "
                + candidate.detail() + "。是否播放这首？";
    }

    private static AgentMessage lastMessage(List<AgentMessage> conversation) {
        return conversation.isEmpty() ? null : conversation.get(conversation.size() - 1);
    }

    private static boolean conversationAskedForPlayback(List<AgentMessage> conversation) {
        for (int index = conversation.size() - 1, seen = 0; index >= 0 && seen < 4;
                index--, seen++) {
            AgentMessage message = conversation.get(index);
            if (message.getRole() == AgentMessage.Role.ASSISTANT
                    && message.getContent().contains("是否播放这首")) return true;
        }
        return false;
    }

    private static SongRequest exactSong(String text) {
        Matcher match = EXACT_SONG.matcher(text);
        if (!match.matches()) return null;
        String artist = match.group(1).strip();
        String title = match.group(2).strip().replaceAll("^[《〈\"“]+|[》〉\"”]+$", "");
        if (title.length() < 2 || title.length() > 40
                || title.matches("(?:歌|歌曲|歌单|所有歌|所有歌曲|一些歌|几首歌)")) return null;
        return new SongRequest(artist, title);
    }

    private static String artistSongs(String text) {
        Matcher match = ARTIST_SONGS.matcher(text);
        return match.matches() ? match.group(1).strip() : null;
    }

    private static int selectedCandidate(String text, List<QQMusicUiPort.Candidate> candidates) {
        int index = MediaSelectionUtterance.indexChoice(text);
        if (index > 0) {
            return candidates.stream().anyMatch(candidate -> candidate.index() == index)
                    ? index : 0;
        }
        String clean = text.strip().replaceAll("[，,。.!！]+$", "")
                .replaceAll("^(?:选|播放)", "").strip()
                .replaceAll("^[《〈]", "").replaceAll("[》〉]$", "");
        int found = 0;
        for (QQMusicUiPort.Candidate candidate : candidates) {
            if (!candidate.title().equals(clean)) continue;
            if (found != 0) return 0;
            found = candidate.index();
        }
        return found;
    }

    private record SongRequest(String artist, String title) {}
}
