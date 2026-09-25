package com.matrix.agent.data.memory;

import java.util.Locale;
import java.util.regex.Pattern;

/** Historical discovery intent. Spatial 最近/过去 are insufficient by themselves. */
public final class EpisodicQueryIntent {
    private static final Pattern EXPLICIT = Pattern.compile(
            "上次|上回|历史|曾经|回忆|(?:做|去|调|导航|播放|设置)过(?!去|来|高|低|程|点)"
                    + "|\\b(?:last time|previously|history)\\b");
    private static final Pattern TEMPORAL = Pattern.compile(
            "最近(?!的)|刚才|之前|过去(?=有|发生|做|调|设|哪|什么|[0-9一二三四五六七八九十几]+[天周年月])"
                    + "|\\bearlier\\b");
    private static final Pattern RETROSPECTIVE = Pattern.compile(
            "去了|到了|调到|设到|多少度|几度|什么|哪儿|哪里|哪些|记录|发生|是否|有没有"
                    + "|\\b(?:what|where|when|did)\\b");

    private EpisodicQueryIntent() { }

    public static boolean isHistoryQuestion(String text) {
        if (text == null || text.isBlank()) return false;
        String query = text.toLowerCase(Locale.ROOT);
        return EXPLICIT.matcher(query).find()
                || TEMPORAL.matcher(query).find() && RETROSPECTIVE.matcher(query).find();
    }
}
