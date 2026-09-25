package com.matrix.agent.platform.media;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Narrow acknowledgement grammar; an arbitrary new request must never count as consent. */
public final class MediaSelectionUtterance {
    private static final Pattern INDEX_CHOICE = Pattern.compile(
            "^(?:(?:选|播放)\\s*)?第\\s*([1-8一二三四五六七八])\\s*(?:首|个|项)$"
                    + "|^(?:选|播放)\\s*([1-8])$|^([1-8])$");

    private MediaSelectionUtterance() {}

    /** Returns a deliberately narrow explicit choice; a bare acknowledgement is never one. */
    public static int indexChoice(String text) {
        if (text == null) return 0;
        Matcher match = INDEX_CHOICE.matcher(text.strip().replaceAll("[，,。.!！]+$", ""));
        if (!match.matches()) return 0;
        String value = match.group(1) != null ? match.group(1)
                : match.group(2) != null ? match.group(2) : match.group(3);
        int chinese = "一二三四五六七八".indexOf(value);
        return chinese >= 0 ? chinese + 1 : Integer.parseInt(value);
    }

    public static boolean isAffirmative(String text) {
        if (text == null) return false;
        String clean = normalize(text);
        return clean.matches("是(的)?(播放吧?)?|对(的)?(播放吧?)?|好(的)?(播放吧?)?|"
                + "可以(播放吧?)?|确认(播放)?|播放吧?|就(播|放)这首|嗯+|"
                + "(?i:yes|y|ok|okay)");
    }

    public static boolean isConfirmationReply(String text) {
        if (isAffirmative(text)) return true;
        return isNegative(text);
    }

    public static boolean isNegative(String text) {
        if (text == null) return false;
        return normalize(text).matches("不(是|要|要播放|播放|用了)?|否|取消|算了|别播(放)?|"
                + "(?i:no|n|cancel)");
    }

    private static String normalize(String text) {
        return text.strip().replaceAll("[\\s，,。.!！]+", "");
    }
}
