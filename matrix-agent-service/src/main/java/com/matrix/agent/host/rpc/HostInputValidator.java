package com.matrix.agent.host.rpc;

import java.util.UUID;
import java.util.Locale;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

/** Cross-domain validation applied before any persistence, callback registration or scheduling. */
final class HostInputValidator {
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9-]{1,35}");
    private HostInputValidator() {}

    static String requireOperationId(String value) {
        if (value == null) throw new IllegalArgumentException("clientOperationId required");
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("clientOperationId must be a lowercase UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid clientOperationId");
        }
        return value;
    }

    static String requireSessionId(String value) {
        if (value == null) throw new IllegalArgumentException("sessionId required");
        try {
            if (!UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("sessionId must be a lowercase UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        return value;
    }

    static String requireLanguageTag(String value) {
        if (value == null) return null;
        if (!LANGUAGE.matcher(value).matches()
                || Locale.forLanguageTag(value).getLanguage().isEmpty()) {
            throw new IllegalArgumentException("invalid languageTag");
        }
        return value;
    }

    static String boundUtf8(String value, int maxBytes) {
        if (value == null) return "";
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            String unit = new String(Character.toChars(codePoint));
            int next = unit.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + next > maxBytes) break;
            out.append(unit);
            bytes += next;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
