package com.matrix.agent.model;

import com.matrix.agent.contract.ApiProtocol;
import java.util.List;

/** Exercises Android libcore's JSONObject.NULL behavior without an instrumentation APK. */
public final class StreamingProtocolDeviceCheck {
    private StreamingProtocolDeviceCheck() { }

    public static void verifyExplicitNull() throws Exception {
        var anthropic = StreamingToolProtocol.create(ApiProtocol.ANTHROPIC_MESSAGES, List.of(), ignored -> { });
        anthropic.accept("{\"type\":\"message_start\",\"message\":{}}");
        anthropic.accept("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"正文\"}}");
        anthropic.accept("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":null}}");
        anthropic.accept("{\"type\":\"content_block_stop\",\"index\":0}");
        anthropic.accept("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
        if (!anthropic.accept("{\"type\":\"message_stop\"}")
                || !"正文".equals(anthropic.result().getAssistantMessage().getContent())) {
            throw new IllegalStateException("Anthropic explicit null stop_reason");
        }
        var gemini = StreamingToolProtocol.create(ApiProtocol.GEMINI_GENERATE_CONTENT, List.of(), ignored -> { });
        if (gemini.accept("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"前半\"}]},\"finishReason\":null}]}")) {
            throw new IllegalStateException("Gemini explicit null completed stream");
        }
        if (!gemini.accept("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"后半\"}]},\"finishReason\":\"STOP\"}]}")) {
            throw new IllegalStateException("Gemini finish missing");
        }
        if (!"前半后半".equals(gemini.result().getAssistantMessage().getContent())) {
            throw new IllegalStateException("Gemini body truncated");
        }
    }
}
