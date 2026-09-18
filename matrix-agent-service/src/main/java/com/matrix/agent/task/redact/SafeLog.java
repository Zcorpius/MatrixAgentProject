package com.matrix.agent.task.redact;
import com.matrix.agent.task.*;

import com.matrix.agent.contract.ToolCall;

import android.util.Log;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 统一安全日志入口——所有受治理日志(用户输入、Tool 参数、ToolResult message、
 * HTTP 错误响应、模型 raw response)必须过本类,**禁止**在业务代码直接调 {@link android.util.Log}
 * 打印这些字段。
 *
 * <p>治理范围(必须过 SafeLog):
 * <ul>
 *   <li>完整用户输入({@code request.getText()}、{@code command});</li>
 *   <li>Tool 原始参数({@code call.getArguments()});</li>
 *   <li>ToolResult 原始 {@code message} / {@code observedState};</li>
 *   <li>HTTP 错误响应体、模型 raw response;——错误响应可能包含厂商网关返回的诊断信息,
 *       也可能回显请求参数(含 API Key、目的地等);</li>
 *   <li>API Key、Token、Bearer、Authorization Header、用户记忆(value / home_address 等)。</li>
 * </ul>
 *
 * <p>不在治理范围(可直接用 {@link android.util.Log}):仅含元数据,如 sessionId / requestId /
 * capabilityName / iteration count / StopReason / TaskState / 耗时 / 字符数等结构性指标。
 *
 * <p>本类内部委托 {@link AuditRedactor#redact(String)} 复用凭据正则与截断逻辑;
 * 用户输入、Tool 参数、ToolResult message 三个特殊字段加额外占位符包裹,确保即使包含
 * 业务字段也以 redacted 形式落 logcat。
 */
public final class SafeLog {
    /** 用户输入占位符——绝不把原文写 logcat。 */
    public static final String USER_INPUT_PLACEHOLDER = "[user-input-redacted]";
    /** Tool 参数占位符。 */
    public static final String TOOL_ARGS_PLACEHOLDER = "[tool-args-redacted]";
    /** ToolResult message / observedState 占位符。 */
    public static final String TOOL_RESULT_PLACEHOLDER = "[tool-result-redacted]";
    /** HTTP / Provider raw response 占位符。 */
    public static final String PROVIDER_RAW_PLACEHOLDER = "[provider-raw-redacted]";

    private static final int MAX_LOG_CHARS = 2000;
    private static final AuditRedactor SHARED_REDACTOR = new AuditRedactor(MAX_LOG_CHARS);

    /**
     * 受治理日志的 capabilities 集合——这些 cap 的 ToolCall args / ToolResult message
     * **整段** mask,即使内部已有 schema 投影也不在 logcat 中暴露原文结构。
     */
    private static final Set<String> FULLY_REDACTED_CAPABILITIES = new java.util.HashSet<>(
            java.util.Arrays.asList(
                    "memory.preference.save", "memory.preference.get",
                    "memory.semantic.save", "memory.semantic.get"));

    private SafeLog() {}

    public static void i(String tag, String message) {
        Log.i(tag, truncateIfNeeded(redact(message)));
    }

    public static void w(String tag, String message) {
        Log.w(tag, truncateIfNeeded(redact(message)));
    }

    public static void e(String tag, String message) {
        Log.e(tag, truncateIfNeeded(redact(message)));
    }

    public static void d(String tag, String message) {
        Log.d(tag, truncateIfNeeded(redact(message)));
    }

    /**
     * 受治理字段专用:用户输入。
     * <p>默认行为是直接替换为 {@link #USER_INPUT_PLACEHOLDER}——除非 caller 显式传入
     * {@code allowReveal=true}(用于诊断且用户已确认的临时场景)。
     */
    public static void iUserInput(String tag, String prefix, boolean allowReveal, String userInput) {
        String safe = allowReveal ? redact(userInput) : USER_INPUT_PLACEHOLDER;
        Log.i(tag, prefix + safe);
    }

    /**
     * 受治理字段专用:Tool 参数。
     * <p>{@code memory.preference.*} 等 fully-redacted cap 一律替换为占位符;其余 cap 也
     * 不暴露原文 args(凭据正则只识别 API Key,业务字段如 destination / home_address 识别不出)。
     */
    public static void iToolArgs(String tag, String prefix, String capabilityName,
            java.util.Map<String, Object> args) {
        String safe = shouldFullyRedact(capabilityName) ? TOOL_ARGS_PLACEHOLDER
                : (args == null || args.isEmpty() ? "{}" : TOOL_ARGS_PLACEHOLDER);
        Log.i(tag, prefix + safe);
    }

    /** 受治理字段专用:ToolResult message。任何 capability 的 message 都用占位符包裹。 */
    public static void iToolResult(String tag, String prefix, String message) {
        Log.i(tag, prefix + (message == null || message.isEmpty() ? "" : TOOL_RESULT_PLACEHOLDER));
    }

    /** 受治理字段专用:HTTP / 模型 raw response。无论 200 还是 4xx/5xx body 一律占位符。 */
    public static void wProviderRaw(String tag, String prefix, int httpCode, String bodyPreview) {
        Log.w(tag, prefix + "http=" + httpCode + " body=" + PROVIDER_RAW_PLACEHOLDER);
    }

    private static boolean shouldFullyRedact(String capabilityName) {
        return capabilityName != null && FULLY_REDACTED_CAPABILITIES.contains(capabilityName);
    }

    private static String redact(String value) {
        return value == null ? "" : SHARED_REDACTOR.redact(value);
    }

    private static String truncateIfNeeded(String value) {
        if (value == null) return "";
        return value.length() <= MAX_LOG_CHARS
                ? value
                : ModelSanitizer.truncateWithSuffix(value, MAX_LOG_CHARS);
    }
}
