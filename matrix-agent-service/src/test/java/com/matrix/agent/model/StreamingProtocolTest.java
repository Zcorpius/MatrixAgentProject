package com.matrix.agent.model;

import com.matrix.agent.contract.*;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.task.capability.CapabilityRegistry;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public final class StreamingProtocolTest {
    private final List<ToolDefinition> tools = CapabilityRegistry.createDemoRegistry().toToolDefinitions();
    @Test public void readOnlySummaryWithoutToolsBuildsForAllCloudProtocols() throws Exception {
        var conversation = List.of(AgentMessage.user("只根据资料回答"));
        for (ApiProtocol protocol : List.of(ApiProtocol.OPENAI_CHAT, ApiProtocol.ANTHROPIC_MESSAGES,
                ApiProtocol.GEMINI_GENERATE_CONTENT)) {
            var config = new ModelConfig("test", "test", protocol, "https://example.test", "test", "", false,
                    PlannerMode.NATIVE_TOOL_CALLING);
            JSONObject body = switch (protocol) {
                case OPENAI_CHAT -> OpenAiToolProtocol.buildConversationRequest(config, "system", conversation, List.of());
                case ANTHROPIC_MESSAGES -> AnthropicToolProtocol.buildRequest(config, "system", conversation, List.of());
                case GEMINI_GENERATE_CONTENT -> GeminiToolProtocol.buildRequest(config, "system", conversation, List.of());
                default -> throw new AssertionError();
            };
            assertFalse(body.has("tools"));
            assertFalse(body.has("tool_choice"));
        }
        var parser = StreamingToolProtocol.create(ApiProtocol.OPENAI_CHAT, List.of(), ignored -> { });
        parser.accept(openAi("正文", "stop"));
        parser.accept("[DONE]");
        assertEquals("正文", parser.result().getAssistantMessage().getContent());
        var unsolicited = StreamingToolProtocol.create(ApiProtocol.OPENAI_CHAT, List.of(), ignored -> { });
        unsolicited.accept(toolDelta("call_1", "unknown_tool", "{}", "tool_calls"));
        assertThrows(IllegalStateException.class, () -> { unsolicited.accept("[DONE]"); unsolicited.result(); });
    }
    @Test public void openAiTextSurvivesEveryUtf8ByteBoundaryAndRequiresDone() throws Exception {
        List<ModelStreamEvent> events = new ArrayList<>();
        var p = StreamingToolProtocol.create(ApiProtocol.OPENAI_CHAT, tools, events::add);
        String wire = sse(openAi("你好🚘", null)) + sse(openAi("！", "stop")) + "data: [DONE]\n\n";
        byte[] bytes = wire.getBytes(StandardCharsets.UTF_8);
        InputStream input = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(1, len)); }
        };
        SseHttpTransport.read(input, new CancellationToken(), p);
        assertEquals("你好🚘！", p.result().getAssistantMessage().getContent());
        assertEquals("你好🚘！", visible(events));
        var truncated = StreamingToolProtocol.create(ApiProtocol.OPENAI_CHAT, tools, ignored -> { });
        assertThrows(EOFException.class, () -> SseHttpTransport.read(new ByteArrayInputStream(
                (sse(openAi("半句", "stop"))).getBytes(StandardCharsets.UTF_8)), null, truncated));
    }

    @Test public void openAiToolArgumentsStayPrivateUntilCompleteAndLengthNeverExecutes() throws Exception {
        for (String reason : List.of("tool_calls", "length")) {
            List<ModelStreamEvent> events = new ArrayList<>();
            var p = StreamingToolProtocol.create(ApiProtocol.OPENAI_CHAT, tools, events::add);
            p.accept(toolDelta("call_1", "vehicle_climate_set_temperature", "{\"temperature\":", null));
            assertThrows(IllegalStateException.class, p::result);
            p.accept(toolDelta("", "", "23}", reason));
            p.accept("[DONE]");
            var result = p.result();
            assertEquals(reason.equals("tool_calls"), result.hasToolCalls());
            assertEquals("", visible(events));
            if (result.hasToolCalls()) assertEquals(23, result.getToolCalls().get(0).getArguments().get("temperature"));
        }
    }

    @Test public void anthropicRequiresClosedBlocksAndDoesNotPublishThinking() throws Exception {
        List<ModelStreamEvent> events = new ArrayList<>();
        var p = StreamingToolProtocol.create(ApiProtocol.ANTHROPIC_MESSAGES, tools, events::add);
        p.accept("{\"type\":\"message_start\",\"message\":{}}");
        p.accept("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\"}}");
        p.accept("{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"private chain\"}}");
        p.accept("{\"type\":\"content_block_stop\",\"index\":0}");
        p.accept("{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
        p.accept("{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"答复\"}}");
        assertThrows(IllegalStateException.class, () -> p.accept("{\"type\":\"message_stop\"}"));
        p.accept("{\"type\":\"content_block_stop\",\"index\":1}");
        p.accept("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
        assertTrue(p.accept("{\"type\":\"message_stop\"}"));
        assertEquals("答复", p.result().getAssistantMessage().getContent());
        assertEquals("答复", visible(events));
    }

    @Test public void explicitNullFinishFieldsDoNotCompleteOrCorruptStreams() throws Exception {
        var anthropic = StreamingToolProtocol.create(ApiProtocol.ANTHROPIC_MESSAGES, tools, ignored -> { });
        anthropic.accept("{\"type\":\"message_start\",\"message\":{}}");
        anthropic.accept("{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"正文\"}}");
        anthropic.accept("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":null}}");
        anthropic.accept("{\"type\":\"content_block_stop\",\"index\":0}");
        assertThrows(IllegalStateException.class, anthropic::result);
        anthropic.accept("{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}");
        assertTrue(anthropic.accept("{\"type\":\"message_stop\"}"));
        assertEquals("正文", anthropic.result().getAssistantMessage().getContent());

        var gemini = StreamingToolProtocol.create(ApiProtocol.GEMINI_GENERATE_CONTENT, tools, ignored -> { });
        assertFalse(gemini.accept("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"前半\"}]},\"finishReason\":null}]}"));
        assertThrows(IllegalStateException.class, gemini::result);
        assertTrue(gemini.accept("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"后半\"}]},\"finishReason\":\"STOP\"}]}"));
        assertEquals("前半后半", gemini.result().getAssistantMessage().getContent());
    }

    @Test public void geminiDropsThoughtAndRequiresValidFinishEvenWithTools() throws Exception {
        List<ModelStreamEvent> events = new ArrayList<>();
        var p = StreamingToolProtocol.create(ApiProtocol.GEMINI_GENERATE_CONTENT, tools, events::add);
        p.accept("{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"private chain\"},{\"text\":\"正文\"}]}}]}");
        p.accept("{\"candidates\":[{\"finishReason\":\"STOP\"}]}");
        assertEquals("正文", p.result().getAssistantMessage().getContent());
        assertEquals("正文", visible(events));
    }

    @Test public void incrementalMarkupAndJsonEscapeBoundariesNeverLeakProtocol() {
        String raw = "<think>分析{私密}</think>你好🚘<tool_call>{\"secret\":1}</tool_call>结束";
        for (int split = 0; split <= raw.length(); split++) {
            StringBuilder shown = new StringBuilder();
            var parser = new PublicBodyDecoder(shown::append, true);
            parser.append(raw.substring(0, split)); parser.append(raw.substring(split));
            assertTrue("boundary " + split, parser.finish());
            assertEquals("你好🚘结束", shown.toString());
        }
        var hidden = new PublicBodyDecoder(value -> fail("tool JSON leaked"), true);
        for (char c : "<think>{reason}</think>{\"tool_calls\":[]}".toCharArray()) hidden.append(String.valueOf(c));
        assertTrue(hidden.finish());
        StringBuilder summary = new StringBuilder();
        var decoder = new SummaryStreamDecoder(summary::append);
        for (char c : "{\"steps\":[{\"summary\":\"private\"}],\"summary\":\"你好\\n\\uD83D\\uDE98\"}".toCharArray()) decoder.append(String.valueOf(c));
        assertEquals("你好\n🚘", summary.toString());
    }

    @Test public void malformedUtf8OversizedEventsAndErrorsFailClosed() {
        assertThrows(Exception.class, () -> SseHttpTransport.read(new ByteArrayInputStream(
                new byte[]{(byte) 0xc3, (byte) 0x28}), null, ignored -> false));
        assertThrows(IOException.class, () -> SseHttpTransport.read(new ByteArrayInputStream(
                ("data: " + "x".repeat(65_537) + "\n\n").getBytes(StandardCharsets.UTF_8)), null, ignored -> false));
        for (var protocol : List.of(ApiProtocol.OPENAI_CHAT, ApiProtocol.ANTHROPIC_MESSAGES,
                ApiProtocol.GEMINI_GENERATE_CONTENT)) {
            var parser = StreamingToolProtocol.create(protocol, tools, ignored -> { });
            assertThrows(Exception.class, () -> parser.accept("{\"type\":\"error\",\"error\":{\"message\":\"secret\"}}"));
        }
    }
    private static String openAi(String content, String finish) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("content", content)).put("finish_reason", finish == null ? JSONObject.NULL : finish))).toString();
    }
    private static String toolDelta(String id, String name, String arguments, String finish) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("tool_calls", new JSONArray().put(new JSONObject().put("index", 0)
                        .put("id", id).put("function", new JSONObject().put("name", name).put("arguments", arguments)))))
                .put("finish_reason", finish == null ? JSONObject.NULL : finish))).toString();
    }
    private static String sse(String value) { return "data: " + value + "\n\n"; }
    private static String visible(List<ModelStreamEvent> events) {
        return events.stream().filter(e -> e instanceof ModelStreamEvent.BodyDelta)
                .map(e -> ((ModelStreamEvent.BodyDelta) e).text()).collect(java.util.stream.Collectors.joining());
    }
}
