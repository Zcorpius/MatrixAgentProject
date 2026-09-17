package com.matrix.agent.task;

import android.util.Log;

import com.matrix.agent.task.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 喂回 LLM conversation 的 Observation 清洗器——**只脱凭据,保留任务语义**。
 *
 * <p>数据边界(修复后的正确方向):
 * <ul>
 *   <li>Conversation(模型侧,本类处理):保留 memory.preference.* 的真实 value、
 *       导航地址、联系人等完成任务所需的语义;只 mask API Key / Bearer Token / 凭据。
 *       否则模型无法回答"我喜欢多少度"——memory.preference.get 的真实值必须给模型看到。</li>
 *   <li>Trajectory / UI / Log(审计侧):走 {@link AuditRedactor},字段级脱敏。</li>
 * </ul>
 *
 * <p>本类还做超长截断({@code maxChars}),避免单条 Observation 把 conversation 撑爆。
 */
public final class ModelSanitizer {
    private static final String TAG = "MatrixAgent";
    private static final Pattern[] SECRET_PATTERNS = {
            Pattern.compile("sk-ant-[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("sk-[A-Za-z0-9]{16,}"),
            Pattern.compile("AIza[A-Za-z0-9_\\-]{16,}"),
            Pattern.compile("ghp_[A-Za-z0-9]{16,}"),
            Pattern.compile("(?i)Bearer\\s+\\S+")
    };
    private static final String TRUNCATED_PREFIX = "[truncated ";
    private static final String TRUNCATED_SUFFIX = " chars]";

    private final int maxChars;

    public ModelSanitizer(int maxChars) {
        if (maxChars <= 0) throw new IllegalArgumentException("maxChars 必须大于 0");
        this.maxChars = maxChars;
    }

    public String sanitize(String content) {
        if (content == null) return "";
        String result = content;
        int totalMasked = 0;
        for (Pattern pattern : SECRET_PATTERNS) {
            Matcher m = pattern.matcher(result);
            int hits = 0;
            while (m.find()) hits++;
            if (hits > 0) {
                result = m.replaceAll("***");
                totalMasked += hits;
            }
        }
        boolean truncated = result.length() > maxChars;
        if (truncated) {
            result = truncateWithSuffix(result, maxChars);
        }
        if (totalMasked > 0 || truncated) {
            Log.d(TAG, "[Sanitize] applied secretMask=" + totalMasked
                    + " truncated=" + truncated
                    + " origChars=" + content.length() + " sanitizedChars=" + result.length());
        }
        return result;
    }

    /**
     * 截断 + 追加 {@code [truncated N chars]} 后缀,**确保最终总长度 ≤ maxChars**。
     * 旧实现 {@code substring(0, maxChars) + suffix} 会让总长 > maxChars。
     */
    static String truncateWithSuffix(String input, int maxChars) {
        int originalLen = input.length();
        int keep = Math.min(originalLen, maxChars);
        String suffix;
        while (true) {
            suffix = TRUNCATED_PREFIX + (originalLen - keep) + TRUNCATED_SUFFIX;
            if (keep + suffix.length() <= maxChars || keep <= 1) break;
            keep--;
        }
        return input.substring(0, keep) + suffix;
    }

    /**
     * 递归清洗 Map——保留任务语义,但 String value 过 secret mask(凭据)。
     * 嵌套 Map / List 都会逐层处理。
     */
    public Map<String, Object> sanitizeMap(Map<String, Object> observed) {
        return sanitizeMapRecursive(observed);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMapRecursive(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return Collections.emptyMap();
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            sanitized.put(entry.getKey(), sanitizeValue(entry.getValue()));
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map) return sanitizeMapRecursive((Map<String, Object>) value);
        if (value instanceof List) {
            List<Object> list = new ArrayList<>(((List<?>) value).size());
            for (Object item : (List<?>) value) list.add(sanitizeValue(item));
            return list;
        }
        return value instanceof String ? sanitize((String) value) : value;
    }

    /**
     * 清洗 Observation——只 mask 凭据,保留 memory.preference.* 的真实 value。
     * 模型需要这些语义才能完成"我喜欢多少度"之类的后续决策。
     */
    public ToolObservation sanitize(ToolObservation observation) {
        if (observation == null) return null;
        if (observation.getResult() == null) {
            return ToolObservation.fromParts(observation.getToolCallId(),
                    observation.getCapabilityName(), null,
                    sanitize(observation.getRejectionReason()),
                    observation.isCapabilityBlocked());
        }
        ToolResult original = observation.getResult();
        ToolResult sanitized = new ToolResult(original.getStatus(), original.getCapabilityName(),
                sanitize(original.getMessage()), sanitizeMap(original.getObservedState()),
                original.isVerified(), original.getDurationMillis());
        return ToolObservation.fromParts(observation.getToolCallId(), observation.getCapabilityName(),
                sanitized, null, false);
    }
}
