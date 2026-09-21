package com.matrix.agent.api.debug;

/**
 * 调试轨迹 payload 的线格式契约（评估 v1.0 §4.3 debugTraceUi 契约 5/6 的格式化半边）。
 *
 * <p>payload 是以空格分隔的 {@code key=value} 记号序列。在此登记的键是跨进程结构化
 * 契约：Host 发射端用 {@link #token}/{@link #capability} 构造，客户端时间线编译器用
 * {@link #wordValue}/{@link #flagValue} 读取——两端共享同一处定义，而不是各自内联
 * 字符串后静默漂移。结构化键的值必须是单词（不含空白）；自由文本（如 reason、
 * argumentShape、observedKeys 或 reasoning 正文）只能作为末尾记号出现，不参与
 * 结构化解析。</p>
 */
public final class DebugTracePayloads {

    /** 能力名（CapabilityDefinition.name，单词）。 */
    public static final String KEY_CAPABILITY = "cap";
    /** 策略判定结果；值为 {@code true}/{@code false}。 */
    public static final String KEY_ALLOWED = "allowed";
    /** 设备回读核验结果；值为 {@code true}/{@code false}。 */
    public static final String KEY_VERIFIED = "verified";
    /** 工具执行状态（ToolResult.Status 枚举名，单词）。 */
    public static final String KEY_STATUS = "status";

    private DebugTracePayloads() {
    }

    /** 构造单个 {@code key=value} 记号。key 应为已登记的结构化键。 */
    public static String token(String key, Object value) {
        return key + "=" + value;
    }

    /** {@link #KEY_CAPABILITY} 记号的便捷构造。 */
    public static String capability(String capabilityName) {
        return token(KEY_CAPABILITY, capabilityName);
    }

    /**
     * 读取键对应的单词值。只在记号边界匹配（行首或空格后的 {@code key=}），因此
     * 键 {@code cap} 不会误匹配 {@code capability=…}。键不存在时返回 null；
     * 值为空串（{@code key=}）按存在返回空串。
     */
    public static String wordValue(String payload, String key) {
        if (payload == null || key == null || key.isEmpty()) {
            return null;
        }
        int length = payload.length();
        int index = 0;
        while (index < length) {
            int tokenEnd = payload.indexOf(' ', index);
            if (tokenEnd < 0) {
                tokenEnd = length;
            }
            if (matchesKeyAt(payload, index, tokenEnd, key)) {
                return payload.substring(index + key.length() + 1, tokenEnd);
            }
            index = tokenEnd + 1;
        }
        return null;
    }

    /**
     * 读取布尔记号：值为 {@code true}/{@code false} 时返回对应 Boolean；
     * 键缺失、值非布尔或含空白自由文本时返回 null（不猜测）。
     */
    public static Boolean flagValue(String payload, String key) {
        String word = wordValue(payload, key);
        if ("true".equals(word)) return Boolean.TRUE;
        if ("false".equals(word)) return Boolean.FALSE;
        return null;
    }

    private static boolean matchesKeyAt(String payload, int start, int end, String key) {
        // 记号至少要容纳 "key="；值允许为空串。
        if (end - start < key.length() + 1) {
            return false;
        }
        if (payload.charAt(start + key.length()) != '=') {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            if (payload.charAt(start + i) != key.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
