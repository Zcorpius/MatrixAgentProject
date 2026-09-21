package com.matrix.agent.debugtrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 调试轨迹净化器（评估 v1.0 §4.3 契约 6）：**日志与 UI 的共同边界**——所有 debug
 * 事件先经本类再进 logcat / ring buffer。
 *
 * <p>红线（true 模式也不输出）：系统提示词、密钥、认证头、原始审计 payload、完整
 * 位置/联系人/URI。字符串截断到有界长度；Map 值只保留标量短文本，其余折叠为类型名。
 * 禁词命中整行丢弃（不替换——避免半句泄漏）。</p>
 */
public final class DebugTraceRedactor {

    /** 单行上限（UTF-16 chars）——调试行是短摘要，不是日志转储。 */
    static final int MAX_LINE_CHARS = 200;
    /** 禁词命中即整行丢弃。 */
    private static final String[] LINE_KILL_PATTERNS = {
            "Authorization:", "Bearer ", "api-key", "apiKey", "sk-",
            "system prompt", "SystemPrompt", "audit payload", "password=", "token=",
    };
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private DebugTraceRedactor() {
    }

    /**
     * 净化长内容（reasoning 等）：禁词检查但<b>不截断</b>——长度由 Emitter 的
     * 3 KiB 分片管理（评估 v1.0 §4.3 契约 5）。控制字符替换为空格。
     */
    public static String redactBulk(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder safe = new StringBuilder();
        for (String line : raw.replace('\r', '\n').split("\\n")) {
            String redacted = redactLineUnbounded(line);
            if (redacted == null) continue;
            if (safe.length() > 0) safe.append(' ');
            safe.append(redacted);
        }
        return safe.length() == 0 ? null : safe.toString();
    }

    /** 净化单行：禁词整行丢弃、截断、去控制字符。返回 null = 不可显示。 */
    public static String redactLine(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = redactLineUnbounded(raw);
        if (cleaned == null) return null;
        return cleaned.length() > MAX_LINE_CHARS
                ? cleaned.substring(0, MAX_LINE_CHARS) + "…" : cleaned;
    }

    private static String redactLineUnbounded(String raw) {
        if (raw == null || raw.isBlank()) return null;
        for (String pattern : LINE_KILL_PATTERNS) {
            if (raw.contains(pattern)) return null;
        }
        String cleaned = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.isEmpty()) return null;
        // 保留工程诊断语义，同时抹去最常见的可直接识别个人/访问数据。无法可靠分类的
        // 敏感整行由上方 kill-pattern 直接丢弃，宁可少显示也不显示半段密钥。
        cleaned = PHONE.matcher(cleaned).replaceAll("[phone]");
        cleaned = EMAIL.matcher(cleaned).replaceAll("[email]");
        cleaned = ID_CARD.matcher(cleaned).replaceAll("[id-card]");
        return URL.matcher(cleaned).replaceAll("[url]");
    }

    /** 净化键值摘要：值只保留标量短文本；对象/数组折叠为类型名。 */
    public static String redactEntry(String key, Object value) {
        String rendered = scalarOf(value);
        if (rendered == null) {
            rendered = value == null ? "null" : value.getClass().getSimpleName();
        }
        return redactLine(key + "=" + rendered);
    }

    /** 批量净化 Map 为 "k=v" 行（保持插入序）。 */
    public static List<String> redactMap(Map<String, Object> values) {
        List<String> lines = new ArrayList<>();
        if (values == null) {
            return lines;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String line = redactEntry(entry.getKey(), entry.getValue());
            if (line != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * 工具调用的参数形状，而非参数值。
     *
     * <p>调试面板需要让工程师知道模型调用了哪一种能力，却不能因为打开 debug UI 就暴露
     * 地址、联系人、记忆值等业务参数。此方法仅保留受限的键名与值类型；数值也不直出，
     * 用户可从独立、写时净化的能力事实轨迹看到请求/核验结果。</p>
     */
    public static String argumentShape(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return "none";
        List<String> pieces = new ArrayList<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey() == null ? "?" : entry.getKey()
                    .replaceAll("[^A-Za-z0-9_.-]", "_");
            if (key.length() > 48) key = key.substring(0, 48) + "…";
            Object value = entry.getValue();
            String type = value == null ? "null" : value instanceof Number ? "number"
                    : value instanceof Boolean ? "boolean" : value instanceof String ? "string"
                    : value.getClass().getSimpleName();
            pieces.add(key + ":" + type);
        }
        return String.join(",", pieces);
    }

    private static String scalarOf(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.length() > 0 && text.length() <= 64 && !text.contains("\n")
                    ? text : null;
        }
        return null;
    }
}
