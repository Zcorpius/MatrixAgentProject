package com.matrix.agent.model;

import com.matrix.agent.task.tool.ToolCall;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析端侧模型的输出文本为 {@link ToolCall} 列表。
 *
 * <p>支持两种主流 tool call 输出格式：
 * <ul>
 *   <li><b>Qwen3 / Hermes 风格</b>：{@code <tool_call>{"name":...,"arguments":...}</tool_call>}
 *       （每个 call 一个标签，Qwen3 的 jinja.chat_template 默认产出此格式）；</li>
 *   <li><b>OpenAI 风格</b>：{@code {"tool_calls":[{id,type,function:{name,arguments}}]}}。</li>
 * </ul>
 *
 * <p><b>fail-closed</b>：任一 call 校验失败（未知工具名 / 重复 ID / arguments JSON 损坏）
 * 即判<b>整轮</b> protocolError，<b>不执行剩余有效 call</b>。
 *
 * <p>{@code LENGTH/CANCELLED/FAILED} 已由 OnDeviceModelGateway 过滤，不进本方法。
 */
final class OnDeviceToolCallParser {

    private OnDeviceToolCallParser() {}

    /** 匹配 Qwen3/Hermes 的 <tool_call>{json}</tool_call> 标签（非贪婪，DOTALL 跨行）。 */
    private static final Pattern TOOL_CALL_TAG =
            Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL);
    /** 匹配 Qwen3 的 <think>...</think> 推理段（strip 时移除，不计入 textContent）。 */
    private static final Pattern THINK_TAG =
            Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    static final class ParseResult {
        final List<ToolCall> calls;
        final String textContent;
        final boolean protocolError;

        private ParseResult(List<ToolCall> calls, String textContent, boolean protocolError) {
            this.calls = calls;
            this.textContent = textContent;
            this.protocolError = protocolError;
        }

        static ParseResult text(String text) {
            return new ParseResult(Collections.<ToolCall>emptyList(), text, false);
        }

        static ParseResult error(String text) {
            return new ParseResult(Collections.<ToolCall>emptyList(), text, true);
        }
    }

    /** 单个未校验的 call 候选（name/arguments 可能非法，由 parse 统一 fail-closed 校验）。 */
    private static final class RawCall {
        final String name;
        final Object argumentsRaw; // JSONObject / String / null
        final String id;
        RawCall(String name, Object argumentsRaw, String id) {
            this.name = name;
            this.argumentsRaw = argumentsRaw;
            this.id = id;
        }
    }

    static ParseResult parse(String output, Map<String, String> modelToCapability) {
        if (output == null || output.trim().isEmpty()) return ParseResult.text("");

        // 检测未闭合 <tool_call>（截断/畸形）→ 整轮 fail-closed，不当文本跳过让后续有效 call 执行
        // 开闭标签数量不匹配（含多余 </tool_call>）→ 整轮 fail-closed，不让有效 call 绕过校验
        if (countStr(output, "<tool_call>") != countStr(output, "</tool_call>")) {
            return ParseResult.error(stripCallSegments(output));
        }

        // 优先 Qwen3 <tool_call> 标签格式；若无，回退 OpenAI tool_calls JSON 格式
        List<RawCall> rawCalls = extractToolCallTags(output);
        if (rawCalls.isEmpty()) {
            rawCalls = extractOpenAiToolCalls(output);
        }
        if (rawCalls.isEmpty()) return ParseResult.text(output);

        String strippedText = stripCallSegments(output);

        List<ToolCall> calls = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (RawCall rc : rawCalls) {
            if (rc.name == null || rc.name.isEmpty()) return ParseResult.error(strippedText); // 缺 name → fail
            String capability = modelToCapability.get(rc.name);
            if (capability == null) return ParseResult.error(strippedText); // 未知工具 → fail

            Map<String, Object> args;
            try {
                args = parseArguments(rc.argumentsRaw);
            } catch (JSONException e) {
                return ParseResult.error(strippedText); // 坏 JSON → fail
            }

            String id = (rc.id == null || rc.id.isEmpty()) ? stableId(rc.name, args, calls.size()) : rc.id;
            if (!seenIds.add(id)) return ParseResult.error(strippedText); // 重复 id → fail

            calls.add(ToolCall.withId(id, capability, args));
        }
        return new ParseResult(calls, strippedText, false);
    }

    /** 提取所有 <tool_call>{json}</tool_call> 标签；标签内 JSON 损坏 → name 空（触发 fail-closed）。 */
    private static List<RawCall> extractToolCallTags(String output) {
        List<RawCall> list = new ArrayList<>();
        Matcher m = TOOL_CALL_TAG.matcher(output);
        while (m.find()) {
            String content = m.group(1).trim();
            try {
                JSONObject o = new JSONObject(content);
                String name = o.optString("name", "");
                Object args = o.opt("arguments");
                String id = o.optString("id", ""); // Qwen3 通常无 id
                list.add(new RawCall(name, args, id.isEmpty() ? null : id));
            } catch (JSONException e) {
                // 成对标签但内容非法（非 JSON / 缺字段）→ fail-closed，不跳过让后续有效 call 执行
                list.add(new RawCall("", null, null));
            }
        }
        return list;
    }

    /** 提取 OpenAI 风格 {"tool_calls":[...]} / function_call；无则返回 empty。 */
    private static List<RawCall> extractOpenAiToolCalls(String output) {
        List<RawCall> list = new ArrayList<>();
        JSONObject root = extractJsonObject(output);
        if (root == null) return list;
        JSONArray toolCalls = root.optJSONArray("tool_calls");
        if (toolCalls == null) {
            JSONObject fc = root.optJSONObject("function_call"); // 旧 v1
            if (fc != null) {
                toolCalls = new JSONArray();
                toolCalls.put(fc);
            }
        }
        if (toolCalls == null) return list;
        for (int i = 0; i < toolCalls.length(); i++) {
            JSONObject item = toolCalls.optJSONObject(i);
            if (item == null) {
                list.add(new RawCall("", null, null));
                continue;
            }
            JSONObject function = item.optJSONObject("function");
            String name = function != null ? function.optString("name", "") : item.optString("name", "");
            Object args = function != null ? function.opt("arguments") : item.opt("arguments");
            String id = item.optString("id", "");
            list.add(new RawCall(name, args, id.isEmpty() ? null : id));
        }
        return list;
    }

    private static Map<String, Object> parseArguments(Object raw) throws JSONException {
        if (raw == null) return new LinkedHashMap<>();
        if (raw instanceof JSONObject) return toMap((JSONObject) raw);
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.isEmpty()) return new LinkedHashMap<>();
            return toMap(new JSONObject(s));
        }
        // 非对象/字符串（JSONArray/Number/Boolean）→ fail-closed（不接受畸形 + 丢参数）
        throw new JSONException("arguments 必须是 JSON object 或 string，实际: " + raw.getClass().getSimpleName());
    }

    private static Map<String, Object> toMap(JSONObject o) throws JSONException {
        Map<String, Object> m = new LinkedHashMap<>();
        Iterator<String> keys = o.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            Object v = o.get(k);
            if (v instanceof JSONArray) {
                List<Object> list = new ArrayList<>();
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    Object e = a.opt(i);
                    if (e instanceof JSONObject) list.add(toMap((JSONObject) e));
                    else list.add(e);
                }
                m.put(k, list);
            } else if (v instanceof JSONObject) {
                m.put(k, toMap((JSONObject) v));
            } else {
                m.put(k, v);
            }
        }
        return m;
    }

    /** 提取首个 JSON 对象：整串 → ```json fence → 首 '{' 起的子串。 */
    private static JSONObject extractJsonObject(String output) {
        String trimmed = output.trim();
        JSONObject o = tryObject(trimmed);
        if (o != null) return o;
        int fence = trimmed.indexOf("```");
        if (fence >= 0) {
            int nl = trimmed.indexOf('\n', fence);
            int end = trimmed.indexOf("```", fence + 3);
            if (nl >= 0 && end > nl) {
                o = tryObject(trimmed.substring(nl + 1, end).trim());
                if (o != null) return o;
            }
        }
        int brace = trimmed.indexOf('{');
        if (brace >= 0) {
            o = tryObject(trimmed.substring(brace));
            if (o != null) return o;
        }
        return null;
    }

    private static JSONObject tryObject(String s) {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            return null;
        }
    }

    /** 移除 <tool_call>...</tool_call>、<think>...</think> 段，剩余作为 assistant 文本（可空）。 */
    private static String stripCallSegments(String output) {
        String result = output;
        result = TOOL_CALL_TAG.matcher(result).replaceAll("");
        result = THINK_TAG.matcher(result).replaceAll("");
        result = result.replaceAll("```(json)?\\s*", "").trim();
        return result;
    }

    private static String stableId(String name, Map<String, Object> args, int index) {
        String base = name + ":" + args.toString();
        int h = base.hashCode();
        // Math.abs(Integer.MIN_VALUE) 溢出仍负——用无符号转换避免畸形 id
        return "call_" + Integer.toUnsignedString(h, 36) + "_" + index;
    }

    private static int countStr(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
