package com.matrix.agent.task.policy;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.platform.media.ExplicitMediaTarget;
import com.matrix.agent.platform.media.MediaApp;
import com.matrix.agent.platform.media.MediaSwitchIntent;
import com.matrix.agent.task.capability.MediaCapabilities;

import java.util.Locale;
import java.util.regex.Pattern;

/** Prevents resume-current-queue from being mistaken for selection of requested content. */
public final class MediaSelectionPolicy {
    private static final Pattern SELECTION_FIRST = Pattern.compile("先(?:确认|搜索|查找|选曲)");
    private static final Pattern PLAY = Pattern.compile("播放|继续|接着|恢复|\\b(?:play|resume)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPEN_APP = Pattern.compile("打开|启动|开启|进入|\\b(?:open|launch)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNRESOLVED_TARGET = Pattern.compile(
            "播放\\s*(?:那个|这个|它)(?:[。！!？?，,\\s]|$)|(?:换|切换).{0,6}(?:平台|应用)|"
                    + "\\bplay\\s+(?:that|it)\\b|\\bswitch\\s+(?:platform|app)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPECIFIC_CONTENT = Pattern.compile(
            "的歌|歌曲|歌手|歌单|专辑|这首歌|那首歌|"
                    + "\\bsongs?\\s+by\\b|\\b(album|playlist)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SPECIFIC_BILIBILI_CONTENT = Pattern.compile(
            "(?:哔哩哔哩|哔哩|b站|bilibili)的(?!当前|上一个|这个)",
            Pattern.CASE_INSENSITIVE);

    public PolicyDecision evaluate(AgentRequest request, String capability) {
        String instruction = request.getUserInstructionText();
        MediaApp target = ExplicitMediaTarget.singleTarget(instruction);
        if (MediaCapabilities.QQ_OPEN.equals(capability) && !OPEN_APP.matcher(instruction).find()) {
            return PolicyDecision.denyCapability("打开应用需要用户明确要求；播放会话不可用时应说明原因");
        }
        boolean resume = MediaCapabilities.QQ_PLAY.equals(capability)
                || MediaCapabilities.BILI_RESUME.equals(capability);
        if (resume && target == null && UNRESOLVED_TARGET.matcher(instruction).find()) {
            return PolicyDecision.denyCapability("播放对象或目标平台不明确，需要用户澄清后再执行");
        }
        if (MediaCapabilities.BILI_RESUME.equals(capability)
                && ExplicitMediaTarget.hasBvid(instruction) && !PLAY.matcher(instruction).find()) {
            return PolicyDecision.denyCapability("打开视频不等于授权恢复播放，需用户明确要求播放");
        }
        boolean resumesTarget = target == MediaApp.QQMUSIC && MediaCapabilities.QQ_PLAY.equals(capability)
                || target == MediaApp.BILIBILI && MediaCapabilities.BILI_RESUME.equals(capability);
        if (resumesTarget && (SELECTION_FIRST.matcher(request.getUserInstructionText()).find()
                || MediaSwitchIntent.requestsCandidateSearch(request.getUserInstructionText()))) {
            return PolicyDecision.denyCapability("用户要求先搜索或确认选曲，不能直接恢复当前队列");
        }
        if (MediaCapabilities.BILI_RESUME.equals(capability)
                && SPECIFIC_BILIBILI_CONTENT.matcher(request.getUserInstructionText()).find()) {
            return PolicyDecision.denyCapability(
                    "不能把哔哩哔哩当前队列当作用户指定的视频播放");
        }
        if (!MediaCapabilities.QQ_PLAY.equals(capability)) return null;
        String text = request.getUserInstructionText().toLowerCase(Locale.ROOT);
        if (!SPECIFIC_CONTENT.matcher(text).find()) return null;
        return PolicyDecision.denyCapability(
                "当前 QQ 音乐 Tool 只能恢复已有队列，不能按歌曲、歌手、专辑或歌单选曲");
    }
}
