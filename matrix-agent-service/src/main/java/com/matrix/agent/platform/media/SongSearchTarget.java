package com.matrix.agent.platform.media;

import com.matrix.agent.contract.ToolCall;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/** Model-supplied entities, grounded against actual rows before proposing one for confirmation. */
record SongSearchTarget(String artist, String title) {
    static SongSearchTarget from(ToolCall call) throws MediaPlatformException {
        return new SongSearchTarget(entity(call, "artist"), entity(call, "title"));
    }

    int confirmableIndex(List<QQMusicUiPort.Candidate> candidates) {
        // An artist-only or free-text search is a list request, not a specific song selection.
        if (title.isEmpty()) return 0;
        int match = 0;
        for (var candidate : candidates) {
            if (!same(title, candidate.title())
                    || (!artist.isEmpty() && !same(artist, primaryArtist(candidate.detail())))) {
                continue;
            }
            if (match != 0) return 0;
            match = candidate.index();
        }
        return match;
    }

    private static String entity(ToolCall call, String key) throws MediaPlatformException {
        if (!call.getArguments().containsKey(key)) return "";
        Object raw = call.argument(key);
        if (!(raw instanceof String value) || value.length() > 64
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new MediaPlatformException("INVALID_ARGUMENT");
        }
        return value.strip();
    }

    private static String primaryArtist(String detail) {
        int divider = detail.indexOf('·');
        return (divider < 0 ? detail : detail.substring(0, divider)).strip();
    }

    private static boolean same(String expected, String actual) {
        return normalize(expected).equals(normalize(actual));
    }

    private static String normalize(String value) {
        // Preserve punctuation and version suffixes: a live recording is a distinct candidate.
        return Normalizer.normalize(value, Normalizer.Form.NFKC).strip()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
