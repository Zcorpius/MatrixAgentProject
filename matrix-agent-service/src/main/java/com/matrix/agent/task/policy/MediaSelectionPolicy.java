package com.matrix.agent.task.policy;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.MediaCapabilities;

import java.util.Locale;
import java.util.regex.Pattern;

/** Prevents resume-current-queue from being mistaken for selection of requested content. */
public final class MediaSelectionPolicy {
    private static final Pattern SPECIFIC_CONTENT = Pattern.compile(
            "的歌|歌曲|歌手|歌单|专辑|这首歌|那首歌|"
                    + "\\bsongs?\\s+by\\b|\\b(album|playlist)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPECIFIC_BILIBILI_CONTENT = Pattern.compile(
            "(?:哔哩哔哩|哔哩|b站|bilibili)的(?!当前|上一个|这个)",
            Pattern.CASE_INSENSITIVE);

    public PolicyDecision evaluate(AgentRequest request, String capability) {
        if (MediaCapabilities.BILI_RESUME.equals(capability)
                && SPECIFIC_BILIBILI_CONTENT.matcher(request.getText()).find()) {
            return PolicyDecision.denyCapability(
                    "不能把哔哩哔哩当前队列当作用户指定的视频播放");
        }
        if (!MediaCapabilities.QQ_PLAY.equals(capability)) return null;
        String text = request.getText().toLowerCase(Locale.ROOT);
        if (!SPECIFIC_CONTENT.matcher(text).find()) return null;
        return PolicyDecision.denyCapability(
                "当前 QQ 音乐 Tool 只能恢复已有队列，不能按歌曲、歌手、专辑或歌单选曲");
    }
}
