package com.matrix.agent.host;

import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.ConfirmationDecision;
import com.matrix.agent.api.agent.SteerRequest;
import com.matrix.agent.api.common.ParcelSchema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Server-side validation and canonical request digests; clients are never trusted to validate. */
final class AgentRequestValidator {
    private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z0-9-]{1,35}");
    private static final int MAX_TEXT_UTF8_BYTES = 16 * 1024;
    private static final int MAX_REPROMPT_UTF8_BYTES = 2 * 1024;

    private AgentRequestValidator() {}

    static void validateSubmit(AgentRequest request) {
        if (request == null || request.schemaVersion != ParcelSchema.CURRENT
                || !isLowerUuid(request.clientRequestId)
                || request.clientSessionId == null || !SESSION.matcher(request.clientSessionId).matches()
                || request.text == null || request.text.length() < AgentRequest.TEXT_MIN
                || request.text.length() > AgentRequest.TEXT_MAX
                || request.text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_UTF8_BYTES
                || (request.inputSource != AgentRequest.INPUT_TEXT
                    && request.inputSource != AgentRequest.INPUT_VOICE)
                || request.languageTag == null || !LANGUAGE.matcher(request.languageTag).matches()
                || Locale.forLanguageTag(request.languageTag).getLanguage().isEmpty()) {
            throw new IllegalArgumentException("invalid agent request");
        }
    }

    static void validateSteer(SteerRequest request) {
        if (request == null || request.schemaVersion != ParcelSchema.CURRENT) {
            throw new IllegalArgumentException("invalid steer request");
        }
        if (request.type == SteerRequest.TYPE_REPROMPT) {
            if (request.text == null || request.text.length() < SteerRequest.REPROMPT_MIN
                    || request.text.length() > SteerRequest.REPROMPT_MAX
                    || request.text.getBytes(StandardCharsets.UTF_8).length > MAX_REPROMPT_UTF8_BYTES) {
                throw new IllegalArgumentException("invalid reprompt");
            }
        } else if (request.type != SteerRequest.TYPE_DEFER || request.text != null) {
            throw new IllegalArgumentException("invalid steer type");
        }
    }

    static void validateConfirmation(ConfirmationDecision decision) {
        if (decision == null || decision.schemaVersion != ParcelSchema.CURRENT
                || !isLowerUuid(decision.confirmationId)) {
            throw new IllegalArgumentException("invalid confirmation");
        }
    }

    static boolean isLowerUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) 0);
                digest.update(bytes);
            }
            byte[] bytes = digest.digest();
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
