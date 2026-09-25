package com.matrix.agent.task.skill;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.CancellableModelCall;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.platform.media.BilibiliUiPort;
import com.matrix.agent.platform.media.ExplicitMediaTarget;
import com.matrix.agent.platform.media.MediaApp;
import com.matrix.agent.platform.media.MediaSelectionUtterance;
import com.matrix.agent.platform.media.PendingBilibiliSelection;
import com.matrix.agent.task.capability.MediaCapabilities;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Title search and explicit selection; AgentEngine retains policy, Tool execution and audit. */
public final class BilibiliTitleGateway implements ModelGateway {
    private static final Pattern TITLE_REQUEST = Pattern.compile(
            "(?is)^\\s*(?:请|帮我)?\\s*(?:播放|打开|看|观看|搜索)\\s*"
                    + "(?:哔哩哔哩|哔哩|b站|bilibili)(?:的|上(?:的)?)\\s*"
                    + "(.{1,64}?)\\s*[。！!]?\\s*$");

    private final ModelGateway delegate;
    private final PendingBilibiliSelection pending;

    public BilibiliTitleGateway(ModelGateway delegate, PendingBilibiliSelection pending) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.pending = Objects.requireNonNull(pending, "pending");
    }

    @Override public ExecutionLane executionLane() { return delegate.executionLane(); }

    @Override public CancellableModelCall prepare(ModelTurnRequest request) {
        if (ExplicitMediaTarget.singleTarget(request.getAgentRequest().getText())
                == MediaApp.QQMUSIC) {
            pending.discardBilibili(request.getAgentRequest().getSessionId());
        }
        return handles(request) ? ModelGateway.super.prepare(request) : delegate.prepare(request);
    }

    @Override public ModelTurn decide(ModelTurnRequest request) {
        AgentMessage last = lastMessage(request.getConversation());
        if (last != null && last.getRole() == AgentMessage.Role.TOOL) {
            if (MediaCapabilities.BILI_SEARCH.equals(last.getToolName())) return afterSearch(request, last);
            if (MediaCapabilities.BILI_OPEN_RESULT.equals(last.getToolName())) return afterOpen(last);
        }
        String text = request.getAgentRequest().getText();
        String sessionId = request.getAgentRequest().getSessionId();
        Optional<PendingBilibiliSelection.Snapshot> snapshot = pending.bilibiliSnapshot(sessionId);
        if (snapshot.isPresent()) {
            if (ExplicitMediaTarget.singleTarget(text) == MediaApp.QQMUSIC) {
                pending.discardBilibili(sessionId);
                return delegate.decide(request);
            }
            if (MediaSelectionUtterance.isNegative(text)) {
                pending.discardBilibili(sessionId);
                return ModelTurn.directAnswer("好的，不打开哔哩哔哩视频。");
            }
            if (text.strip().equals(snapshot.get().originalRequestText().strip())) {
                return ModelTurn.directAnswer(candidateList(snapshot.get()));
            }
            int choice = snapshot.get().candidates().size() == 1
                    && MediaSelectionUtterance.isAffirmative(text) ? 1
                    : selectedCandidate(text, snapshot.get().candidates());
            if (choice > 0) {
                return ModelTurn.ofToolCalls(List.of(new ToolCall(MediaCapabilities.BILI_OPEN_RESULT,
                        Map.of("index", choice))), "正在打开已指定的哔哩哔哩搜索结果");
            }
            if (MediaSelectionUtterance.isAffirmative(text)) {
                return ModelTurn.directAnswer("搜索到多项结果，请回复序号或完整标题；尚未打开视频。");
            }
        }
        String title = titleRequest(text);
        if (title != null) {
            pending.discardBilibili(sessionId);
            return ModelTurn.ofToolCalls(List.of(new ToolCall(MediaCapabilities.BILI_SEARCH,
                    Map.of("query", title))), "正在哔哩哔哩搜索标题");
        }
        return delegate.decide(request);
    }

    private boolean handles(ModelTurnRequest request) {
        AgentMessage last = lastMessage(request.getConversation());
        if (last != null && last.getRole() == AgentMessage.Role.TOOL
                && (MediaCapabilities.BILI_SEARCH.equals(last.getToolName())
                || MediaCapabilities.BILI_OPEN_RESULT.equals(last.getToolName()))) return true;
        String text = request.getAgentRequest().getText();
        if (titleRequest(text) != null) return true;
        Optional<PendingBilibiliSelection.Snapshot> snapshot = pending.bilibiliSnapshot(
                request.getAgentRequest().getSessionId());
        return snapshot.isPresent() && (MediaSelectionUtterance.isConfirmationReply(text)
                || selectedCandidate(text, snapshot.get().candidates()) > 0
                || text.strip().equals(snapshot.get().originalRequestText().strip()));
    }

    private ModelTurn afterSearch(ModelTurnRequest request, AgentMessage observation) {
        if (!observation.getContent().startsWith("SUCCESS:")) {
            return ModelTurn.directAnswer(observation.getContent().contains("SEARCH_NO_RESULTS")
                    ? "哔哩哔哩没有找到可读取的结果，请换个关键词再试。"
                    : "哔哩哔哩搜索未成功，请打开应用后重试；没有打开视频。");
        }
        return pending.bilibiliSnapshot(request.getAgentRequest().getSessionId())
                .map(value -> ModelTurn.directAnswer(candidateList(value)))
                .orElseGet(() -> ModelTurn.directAnswer("搜索结果已过期，请重新搜索；没有打开视频。"));
    }

    private static ModelTurn afterOpen(AgentMessage observation) {
        if (observation.getContent().startsWith("SUCCESS:")) {
            return ModelTurn.directAnswer("已打开所选哔哩哔哩条目。播放状态尚未确认，番剧可能需要继续选集。");
        }
        if (observation.getContent().contains("SEARCH_RESULT_CHANGED")
                || observation.getContent().contains("SEARCH_CONTEXT_EXPIRED")) {
            return ModelTurn.directAnswer("搜索结果已变化或过期，请重新搜索；没有自动点击其他条目。");
        }
        return ModelTurn.directAnswer("未能确认所选哔哩哔哩条目已打开，请查看应用当前页面；没有自动重试。");
    }

    private static String candidateList(PendingBilibiliSelection.Snapshot snapshot) {
        StringBuilder answer = new StringBuilder(snapshot.candidates().size() == 1
                ? "找到以下哔哩哔哩结果，是否打开这一项？"
                : "找到以下哔哩哔哩结果，请回复序号或完整标题后再打开：");
        for (BilibiliUiPort.Candidate candidate : snapshot.candidates()) {
            answer.append('\n').append(candidate.index()).append(". 《")
                    .append(candidate.title()).append("》— ").append(candidate.kind());
            if (!candidate.detail().isEmpty()) answer.append(" · ").append(candidate.detail());
        }
        return answer.append("\n尚未打开或播放视频。").toString();
    }

    private static int selectedCandidate(String text, List<BilibiliUiPort.Candidate> candidates) {
        int index = MediaSelectionUtterance.indexChoice(text);
        if (index > 0) {
            return candidates.stream().anyMatch(candidate -> candidate.index() == index) ? index : 0;
        }
        String clean = text.strip().replaceAll("[，,。.!！]+$", "")
                .replaceAll("^(?:选|打开|播放)", "").strip()
                .replaceAll("^[《〈]", "").replaceAll("[》〉]$", "");
        int found = 0;
        for (BilibiliUiPort.Candidate candidate : candidates) {
            if (!candidate.title().equals(clean)) continue;
            if (found != 0) return 0;
            found = candidate.index();
        }
        return found;
    }

    private static String titleRequest(String text) {
        if (ExplicitMediaTarget.singleTarget(text) != MediaApp.BILIBILI
                || ExplicitMediaTarget.hasBvid(text)) return null;
        Matcher match = TITLE_REQUEST.matcher(text);
        return match.matches() ? match.group(1).strip() : null;
    }

    private static AgentMessage lastMessage(List<AgentMessage> conversation) {
        return conversation.isEmpty() ? null : conversation.get(conversation.size() - 1);
    }
}
