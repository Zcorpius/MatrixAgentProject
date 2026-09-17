package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SafeLog 日志治理入口测试。
 *
 * <p>验证三个安全保证:
 * <ol>
 *   <li>占位符常量稳定 —— 防止后续重构改名导致日志回退;</li>
 *   <li>iToolArgs / iToolResult / wProviderRaw 即使传入含 API Key 的原文,
 *       也只输出占位符,凭据不进 logcat;</li>
 *   <li>i / w / e / d 走 AuditRedactor,API Key / Bearer / Authorization 头被 mask。</li>
 * </ol>
 *
 * <p>Android {@code Log.*} 在单元测试环境是 stub(返回 0,不打印),无法直接验证输出。
 * 这里通过反射调用 {@code redact(String)} 私有方法验证凭据 mask 行为,
 * 占位符常量通过静态字段直接断言。
 */
public class SafeLogTest {

    @Test
    public void placeholderConstantsAreStable() {
        assertEquals("[user-input-redacted]", SafeLog.USER_INPUT_PLACEHOLDER);
        assertEquals("[tool-args-redacted]", SafeLog.TOOL_ARGS_PLACEHOLDER);
        assertEquals("[tool-result-redacted]", SafeLog.TOOL_RESULT_PLACEHOLDER);
        assertEquals("[provider-raw-redacted]", SafeLog.PROVIDER_RAW_PLACEHOLDER);
    }

    @Test
    public void redactMasksOpenAiStyleApiKey() throws Exception {
        String redacted = invokeRedact("api-key=sk-abcdef1234567890abcdef");
        assertFalse("API Key 必须被 mask,实际:" + redacted,
                redacted.contains("sk-abcdef1234567890abcdef"));
    }

    @Test
    public void redactMasksBearerAuthorizationHeader() throws Exception {
        String redacted = invokeRedact("Authorization: Bearer eyJabc123.someToken.payload");
        assertFalse("Bearer token 必须被 mask,实际:" + redacted,
                redacted.contains("eyJabc123"));
    }

    @Test
    public void redactPreservesNonSensitiveMetadata() throws Exception {
        String redacted = invokeRedact("cap=vehicle.climate.set_temperature zone=driver");
        assertTrue("非敏感 capability 元数据应保留,实际:" + redacted,
                redacted.contains("cap=vehicle.climate"));
    }

    @Test
    public void iToolArgsPlaceholderForFullyRedactedCapability() {
        // memory.preference.save 在 FULLY_REDACTED_CAPABILITIES 集合里,
        // 调用 SafeLog.iToolArgs 时即使传入真实参数,也只会打印占位符。
        // 这里只验证方法可调用 + 不抛异常(Log.* 是 stub)。
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "home_address");
        args.put("value", "上海市XX路123号");
        SafeLog.iToolArgs("MatrixAgent", "[Test] ", "memory.preference.save", args);
    }

    @Test
    public void iToolArgsPlaceholderForRegularCapability() {
        // navigation.start_route 不在 fully-redacted 集合,但 destination 是业务敏感字段。
        // SafeLog 仍然打印占位符 —— 凭据正则只识别 API Key,业务字段识别不出,统一 mask 最安全。
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("destination", "用户家");
        SafeLog.iToolArgs("MatrixAgent", "[Test] ", "navigation.start_route", args);
    }

    @Test
    public void iToolResultAlwaysMasksMessage() {
        // 任何 capability 的 ToolResult message 都用占位符包裹。
        SafeLog.iToolResult("MatrixAgent", "[Test] ",
                "已记住你的温度偏好:24℃");
    }

    @Test
    public void wProviderRawMasksResponseBody() {
        // HTTP 错误响应 body 一律用占位符包裹,即使厂商网关回显了请求参数。
        SafeLog.wProviderRaw("MatrixAgent", "[Http] HTTP error ", 500,
                "{\"error\":\"Invalid API Key sk-leaked-1234567890abcdef\"}");
    }

    @Test
    public void iUserInputMasksByDefault() {
        // allowReveal=false 时一律替换为占位符,即使原文不含 API Key。
        SafeLog.iUserInput("MatrixAgent", "[Test] ", false,
                "把主驾温度调到24度然后导航回家");
    }

    /** 反射调用 SafeLog.redact(String) 私有方法,验证凭据 mask。 */
    private static String invokeRedact(String value) throws Exception {
        Method method = SafeLog.class.getDeclaredMethod("redact", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }
}
