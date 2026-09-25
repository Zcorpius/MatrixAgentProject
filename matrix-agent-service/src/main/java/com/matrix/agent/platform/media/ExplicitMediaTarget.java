package com.matrix.agent.platform.media;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Identifies an application explicitly named in the current request. */
public final class ExplicitMediaTarget {
    private static final String QQ_ALIAS = "qq\\s*音乐|qq\\s*music|扣扣音乐";
    private static final String BILIBILI_ALIAS = "哔哩哔哩|哔哩|b\\s*站|bilibili";
    private static final String APP_ALIAS = "(?:" + QQ_ALIAS + "|" + BILIBILI_ALIAS + ")";
    private static final Pattern QQ_NAME = Pattern.compile(QQ_ALIAS, Pattern.CASE_INSENSITIVE);
    private static final Pattern BILIBILI_NAME = Pattern.compile(BILIBILI_ALIAS,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BVID = Pattern.compile(
            "(?<![A-Za-z0-9])(?i:BV)[1-9A-HJ-NP-Za-km-z]{10}(?![A-Za-z0-9])");
    private static final Pattern SWITCH_TARGET = Pattern.compile(
            "(?:切换到|切换为|换到|改用)\\s*(" + APP_ALIAS + ")",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PAUSE_THEN_PLAY = Pattern.compile(
            "(?:^|[\\s，,。；;])(?:请\\s*)?(?:先\\s*)?(?:把\\s*)?"
                    + "暂停\\s*(" + APP_ALIAS + ")\\s*[，,]?\\s*"
                    + "(?:再|然后|后|接着)\\s*播放\\s*(" + APP_ALIAS + ")",
            Pattern.CASE_INSENSITIVE);

    private ExplicitMediaTarget() {}

    public static boolean hasBvid(String text) {
        return text != null && BVID.matcher(text).find();
    }

    public static boolean mentions(String requestText, MediaApp app) {
        if (requestText == null) return false;
        return app == MediaApp.BILIBILI
                ? BILIBILI_NAME.matcher(requestText).find() || hasBvid(requestText)
                : QQ_NAME.matcher(requestText).find();
    }

    public static boolean hasSwitchCue(String requestText) {
        if (requestText == null) return false;
        return requestText.contains("切换") || requestText.contains("换到")
                || requestText.contains("改用");
    }

    /** Directional target takes precedence when both source and target are mentioned. */
    public static MediaApp switchTarget(String requestText) {
        if (requestText == null) return null;
        Matcher matches = SWITCH_TARGET.matcher(requestText);
        MediaApp target = null;
        while (matches.find()) {
            MediaApp current = appOf(matches.group(1));
            if (target != null && target != current) return null;
            target = current;
        }
        if (target != null) return target;
        target = handoffTarget(requestText);
        return target != null ? target : singleTarget(requestText);
    }

    /** Target of an imperative "pause source, then play target" request. */
    public static MediaApp handoffTarget(String requestText) {
        if (requestText == null) return null;
        Matcher matches = PAUSE_THEN_PLAY.matcher(requestText);
        MediaApp target = null;
        while (matches.find()) {
            MediaApp source = appOf(matches.group(1));
            MediaApp current = appOf(matches.group(2));
            if (source == current || (target != null && target != current)) return null;
            target = current;
        }
        return target;
    }

    private static MediaApp appOf(String alias) {
        return QQ_NAME.matcher(alias).matches() ? MediaApp.QQMUSIC : MediaApp.BILIBILI;
    }

    /** Returns null when neither or both apps are explicitly named. */
    public static MediaApp singleTarget(String requestText) {
        if (requestText == null) return null;
        boolean bilibili = mentions(requestText, MediaApp.BILIBILI);
        boolean qqMusic = mentions(requestText, MediaApp.QQMUSIC);
        if (bilibili == qqMusic) return null;
        return bilibili ? MediaApp.BILIBILI : MediaApp.QQMUSIC;
    }
}
