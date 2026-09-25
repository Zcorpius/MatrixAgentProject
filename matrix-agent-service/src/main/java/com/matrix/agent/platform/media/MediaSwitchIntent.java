package com.matrix.agent.platform.media;

import java.util.regex.Pattern;

/** One lexical decision shared by Skill selection, the hard Guard and source-pause policy. */
public final class MediaSwitchIntent {
    private static final Pattern NEGATED_CUE = Pattern.compile(
            "(?:别|不要|不用|无需|不是要|不|取消|停止)"
                    + "(?:再|想|需要|要)?\\s*(?:切换|换到|改用)");

    private MediaSwitchIntent() {}

    public static boolean isRequested(String text) {
        if (text == null || NEGATED_CUE.matcher(text).find()) return false;
        boolean qq = ExplicitMediaTarget.mentions(text, MediaApp.QQMUSIC);
        boolean bilibili = ExplicitMediaTarget.mentions(text, MediaApp.BILIBILI);
        if (ExplicitMediaTarget.hasSwitchCue(text)) {
            return qq || bilibili || text.contains("媒体") || text.contains("来源")
                    || text.contains("音乐") || text.contains("歌曲")
                    || text.contains("视频");
        }
        // Require imperative direction; state descriptions mentioning both verbs are not handoffs.
        return ExplicitMediaTarget.handoffTarget(text) != null;
    }
}
