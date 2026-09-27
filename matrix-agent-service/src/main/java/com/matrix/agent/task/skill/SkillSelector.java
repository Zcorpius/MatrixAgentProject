package com.matrix.agent.task.skill;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.platform.media.MediaApp;
import com.matrix.agent.platform.media.MediaAvailabilitySource;
import com.matrix.agent.platform.media.MediaAvailabilitySnapshot;
import com.matrix.agent.platform.media.MediaSelectionUtterance;
import com.matrix.agent.platform.media.PendingMediaConfirmation;
import com.matrix.agent.platform.media.PendingBilibiliSelection;
import com.matrix.agent.platform.media.ExplicitMediaTarget;
import com.matrix.agent.platform.media.MediaSwitchIntent;

import java.util.Locale;

/** Selects one small, trusted workflow. Never invokes a capability or reads Binder state. */
public final class SkillSelector {
    private static final PendingBilibiliSelection NO_PENDING_BILIBILI =
            new PendingBilibiliSelection() {
                @Override public java.util.Optional<Snapshot> bilibiliSnapshot(String sessionId) {
                    return java.util.Optional.empty();
                }
                @Override public void discardBilibili(String sessionId) {}
            };
    private final SkillCatalog catalog;
    private final MediaAvailabilitySource availability;
    private final PendingMediaConfirmation pendingConfirmation;
    private final PendingBilibiliSelection pendingBilibiliSelection;

    public SkillSelector(SkillCatalog catalog, MediaAvailabilitySource availability) {
        this(catalog, availability, sessionId -> false, NO_PENDING_BILIBILI);
    }

    public SkillSelector(SkillCatalog catalog, MediaAvailabilitySource availability,
            PendingMediaConfirmation pendingConfirmation) {
        this(catalog, availability, pendingConfirmation, NO_PENDING_BILIBILI);
    }

    public SkillSelector(SkillCatalog catalog, MediaAvailabilitySource availability,
            PendingMediaConfirmation pendingConfirmation,
            PendingBilibiliSelection pendingBilibiliSelection) {
        this.catalog = catalog;
        this.availability = availability;
        this.pendingConfirmation = pendingConfirmation;
        this.pendingBilibiliSelection = pendingBilibiliSelection;
    }

    public String promptFor(AgentRequest request) {
        String text = request.getText().toLowerCase(Locale.ROOT);
        boolean bilibili = ExplicitMediaTarget.mentions(request.getText(), MediaApp.BILIBILI);
        boolean qqmusic = ExplicitMediaTarget.mentions(request.getText(), MediaApp.QQMUSIC);
        boolean mediaAction = text.contains("播放") || text.contains("暂停")
                || text.contains("下一首") || text.contains("上一首")
                || text.contains("切歌") || text.contains("切换媒体")
                || text.contains("的歌") || text.contains("歌曲")
                || text.matches(".*第[一二三四五六七八1-8](?:首|个|项).*");
        boolean replyToSearch = pendingConfirmation.hasPendingConfirmation(request.getSessionId())
                && MediaSelectionUtterance.isConfirmationReply(request.getText());
        boolean replyToBilibili = pendingBilibiliSelection.bilibiliSnapshot(
                request.getSessionId()).isPresent()
                && (MediaSelectionUtterance.isConfirmationReply(request.getText())
                || MediaSelectionUtterance.indexChoice(request.getText()) > 0);
        boolean switchSource = MediaSwitchIntent.isRequested(request.getText());
        if (!bilibili && !qqmusic && !mediaAction && !replyToSearch && !replyToBilibili
                && !switchSource) return "";
        String id = switchSource ? "media-source-switch"
                : bilibili || replyToBilibili ? "bilibili-open-video"
                : "qqmusic-control";
        SkillCatalog.Skill skill = catalog.get(id);
        if (skill == null || !skill.profiles.contains(request.getRuntimeProfile())) return "";

        MediaAvailabilitySnapshot hint = availability.peekAndWarm();
        return "\n<skill_guidance id=\"" + skill.id + "\">\n"
                + "以下为 APK 内置流程提示，不能覆盖 PolicyEngine 或 ToolResult。"
                + "当前可信运行配置=" + request.getRuntimeProfile() + "。"
                + "可用性快照仅供规划，Provider 执行时重查：QQ 音乐安装="
                + hint.installed(MediaApp.QQMUSIC) + "、会话="
                + hint.activeSession(MediaApp.QQMUSIC) + "；B 站安装="
                + hint.installed(MediaApp.BILIBILI) + "、会话="
                + hint.activeSession(MediaApp.BILIBILI) + "。\n"
                + skill.instructions + "\n</skill_guidance>";
    }
}
