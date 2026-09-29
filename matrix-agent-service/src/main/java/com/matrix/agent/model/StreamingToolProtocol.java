package com.matrix.agent.model;

import com.matrix.agent.contract.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** Provider-specific stream state machines reconstruct a complete response before tool validation. */
abstract class StreamingToolProtocol implements SseHttpTransport.EventConsumer {
    final List<ToolDefinition> tools;
    final ModelStreamSink sink;
    final PublicBodyDecoder body;
    boolean complete;
    String finish = "";
    StreamingToolProtocol(List<ToolDefinition> tools, ModelStreamSink sink) {
        this.tools = tools; this.sink = sink;
        body = new PublicBodyDecoder(text -> sink.accept(new ModelStreamEvent.BodyDelta(text)), false);
    }
    abstract ModelTurn result() throws Exception;
    void requireComplete() {
        if (!complete || finish.isEmpty() || !body.finish()) throw new IllegalStateException("incomplete model stream");
    }
    static StreamingToolProtocol create(ApiProtocol protocol, List<ToolDefinition> tools, ModelStreamSink sink) {
        return switch (protocol) {
            case OPENAI_CHAT -> new OpenAi(tools, sink);
            case ANTHROPIC_MESSAGES -> new Anthropic(tools, sink);
            case GEMINI_GENERATE_CONTENT -> new Gemini(tools, sink);
            default -> throw new IllegalArgumentException("unsupported streaming protocol");
        };
    }
    static int index(JSONObject item) throws Exception {
        Object value = item.get("index");
        if (!(value instanceof Integer) || (int) value < 0 || (int) value >= 32) {
            throw new IllegalStateException("stream index");
        }
        return (int) value;
    }

    private static final class OpenAi extends StreamingToolProtocol {
        private static final class CallParts {
            final StringBuilder id = new StringBuilder(), name = new StringBuilder(), args = new StringBuilder();
        }
        final SortedMap<Integer, CallParts> calls = new TreeMap<>();
        OpenAi(List<ToolDefinition> tools, ModelStreamSink sink) { super(tools, sink); }
        @Override public boolean accept(String data) throws Exception {
            if ("[DONE]".equals(data)) { complete = true; return true; }
            JSONObject json = new JSONObject(data);
            if (json.has("error")) throw new IllegalStateException("provider stream error");
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return false; // usage-only chunk
            if (choices.length() != 1) throw new IllegalStateException("multiple stream choices");
            JSONObject choice = choices.getJSONObject(0);
            if (choice.optInt("index", 0) != 0 || !finish.isEmpty()) throw new IllegalStateException("late stream choice");
            JSONObject delta = choice.optJSONObject("delta");
            if (delta != null) {
                if (!delta.isNull("content")) body.append(delta.optString("content", ""));
                JSONArray parts = delta.optJSONArray("tool_calls");
                if (parts != null) for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    int index = index(part);
                    CallParts call = calls.computeIfAbsent(index, ignored -> new CallParts());
                    call.id.append(part.optString("id", ""));
                    JSONObject function = part.optJSONObject("function");
                    if (function != null) {
                        call.name.append(function.optString("name", ""));
                        String arguments = function.optString("arguments", "");
                        call.args.append(arguments);
                        if (call.id.length() > 256 || call.name.length() > 128 || call.args.length() > 65_536) {
                            throw new IllegalStateException("stream tool size");
                        }
                        sink.accept(new ModelStreamEvent.ToolArgumentsDelta(index, arguments));
                    }
                }
            }
            if (!choice.isNull("finish_reason")) finish = choice.optString("finish_reason", "");
            return false;
        }
        @Override ModelTurn result() throws Exception {
            requireComplete();
            JSONArray array = new JSONArray();
            int next = 0;
            for (var entry : calls.entrySet()) {
                if (entry.getKey() != next++) throw new IllegalStateException("missing tool stream index");
                var call = entry.getValue();
                array.put(new JSONObject().put("id", call.id.toString()).put("type", "function")
                        .put("function", new JSONObject().put("name", call.name.toString()).put("arguments", call.args.toString())));
            }
            return OpenAiToolProtocol.parseConversationResponse(new JSONObject().put("choices", new JSONArray()
                    .put(new JSONObject().put("finish_reason", finish).put("message", new JSONObject()
                            .put("content", body.text()).put("tool_calls", array)))), tools);
        }
    }

    private static final class Anthropic extends StreamingToolProtocol {
        final SortedMap<Integer, JSONObject> blocks = new TreeMap<>();
        final Map<Integer, StringBuilder> arguments = new HashMap<>();
        final Set<Integer> stopped = new HashSet<>();
        boolean started;
        Anthropic(List<ToolDefinition> tools, ModelStreamSink sink) { super(tools, sink); }
        @Override public boolean accept(String data) throws Exception {
            JSONObject json = new JSONObject(data);
            String type = json.getString("type");
            if ("error".equals(type)) throw new IllegalStateException("provider stream error");
            if ("ping".equals(type)) return false;
            if ("message_start".equals(type)) {
                if (started) throw new IllegalStateException("duplicate message start");
                started = true; return false;
            }
            if (!started) throw new IllegalStateException("missing message start");
            switch (type) {
                case "content_block_start" -> {
                    int index = index(json);
                    if (blocks.containsKey(index)) throw new IllegalStateException("duplicate content block");
                    JSONObject block = json.getJSONObject("content_block");
                    blocks.put(index, block);
                    if ("text".equals(block.getString("type"))) body.append(block.optString("text", ""));
                    if ("tool_use".equals(block.getString("type"))) arguments.put(index, new StringBuilder());
                }
                case "content_block_delta" -> {
                    int index = index(json);
                    JSONObject block = blocks.get(index);
                    if (block == null || stopped.contains(index)) throw new IllegalStateException("orphan content delta");
                    JSONObject delta = json.getJSONObject("delta");
                    String kind = delta.getString("type");
                    if ("text_delta".equals(kind)) {
                        if (!"text".equals(block.getString("type"))) throw new IllegalStateException("text type mismatch");
                        body.append(delta.getString("text"));
                    } else if ("input_json_delta".equals(kind)) {
                        StringBuilder args = arguments.get(index);
                        if (args == null) throw new IllegalStateException("tool type mismatch");
                        String part = delta.getString("partial_json");
                        args.append(part);
                        if (args.length() > 65_536) throw new IllegalStateException("tool arguments too large");
                        sink.accept(new ModelStreamEvent.ToolArgumentsDelta(index, part));
                    }
                    // thinking/signature deltas never enter public body or the final text.
                }
                case "content_block_stop" -> {
                    int index = index(json);
                    if (!blocks.containsKey(index) || !stopped.add(index)) throw new IllegalStateException("orphan block stop");
                }
                case "message_delta" -> {
                    JSONObject delta = json.getJSONObject("delta");
                    if (!delta.isNull("stop_reason")) finish = delta.getString("stop_reason");
                }
                case "message_stop" -> {
                    if (stopped.size() != blocks.size()) throw new IllegalStateException("unclosed content blocks");
                    complete = true; return true;
                }
                default -> { /* Forward-compatible unrelated metadata is not display content. */ }
            }
            return false;
        }
        @Override ModelTurn result() throws Exception {
            requireComplete();
            JSONArray content = new JSONArray().put(new JSONObject().put("type", "text").put("text", body.text()));
            for (var entry : blocks.entrySet()) if (arguments.containsKey(entry.getKey())) {
                JSONObject block = entry.getValue();
                String args = arguments.get(entry.getKey()).toString();
                if (!args.isEmpty()) block.put("input", new JSONObject(args));
                content.put(block);
            }
            return AnthropicToolProtocol.parseResponse(new JSONObject().put("content", content).put("stop_reason", finish), tools);
        }
    }

    private static final class Gemini extends StreamingToolProtocol {
        final JSONArray functions = new JSONArray();
        Gemini(List<ToolDefinition> tools, ModelStreamSink sink) { super(tools, sink); }
        @Override public boolean accept(String data) throws Exception {
            JSONObject json = new JSONObject(data);
            if (json.has("error")) throw new IllegalStateException("provider stream error");
            JSONArray candidates = json.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) return false;
            if (candidates.length() != 1) throw new IllegalStateException("multiple stream candidates");
            JSONObject candidate = candidates.getJSONObject(0);
            JSONObject content = candidate.optJSONObject("content");
            JSONArray parts = content == null ? null : content.optJSONArray("parts");
            if (parts != null) for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part.optBoolean("thought", false)) continue;
                if (part.has("text")) body.append(part.getString("text"));
                if (part.has("functionCall")) {
                    if (functions.length() == 32) throw new IllegalStateException("too many tool calls");
                    functions.put(part);
                    sink.accept(new ModelStreamEvent.ToolArgumentsDelta(functions.length() - 1,
                            part.getJSONObject("functionCall").optJSONObject("args") == null ? "{}"
                                    : part.getJSONObject("functionCall").getJSONObject("args").toString()));
                }
            }
            if (!candidate.isNull("finishReason")) finish = candidate.getString("finishReason");
            complete = !finish.isEmpty();
            return complete;
        }
        @Override ModelTurn result() throws Exception {
            requireComplete();
            if (!"STOP".equals(finish) && !"MAX_TOKENS".equals(finish)) return ModelTurn.of(body.text(), FinishReason.NONE);
            JSONArray parts = new JSONArray().put(new JSONObject().put("text", body.text()));
            for (int i = 0; i < functions.length(); i++) parts.put(functions.getJSONObject(i));
            return GeminiToolProtocol.parseResponse(new JSONObject().put("candidates", new JSONArray()
                    .put(new JSONObject().put("finishReason", finish).put("content", new JSONObject().put("parts", parts)))), tools);
        }
    }
}
