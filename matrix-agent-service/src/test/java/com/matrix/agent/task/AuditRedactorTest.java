package com.matrix.agent.task;

import com.matrix.agent.platform.AuditDigest;
import com.matrix.agent.platform.Sha1AuditDigest;
import com.matrix.agent.platform.UnavailableAuditDigest;
import com.matrix.agent.task.redact.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.tool.ToolResult;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/**
 * AuditRedactor 喂 Trajectory/UI/Log——**字段级脱敏**。
 * "用户看自己的偏好不是隐私"在 AAOS 不是通用假设(主驾副驾可能共用 Android User)。
 */
public final class AuditRedactorTest {
    @Test
    public void masksOpenAiStyleApiKey() {
        AuditRedactor redactor = new AuditRedactor(1000);
        assertEquals("key=***", redactor.redact("key=sk-abcd1234abcd1234"));
    }

    @Test
    public void masksBearerAuthorizationHeader() {
        AuditRedactor redactor = new AuditRedactor(1000);
        assertEquals("Authorization: ***", redactor.redact("Authorization: Bearer abc.def.ghi"));
    }

    @Test
    public void truncatesLongContentWithinMaxChars() {
        AuditRedactor redactor = new AuditRedactor(30);
        String result = redactor.redact("1234567890123456789012345678901234567890"); // 40 chars
        assertTrue("expected prefix preserved, got: " + result, result.startsWith("123456789"));
        assertTrue("expected truncation marker, got: " + result, result.contains("[truncated "));
        assertTrue("total length must not exceed maxChars, got: " + result.length(),
                result.length() <= 30);
    }

    /**
     * memory.preference.* 类 capability 的 observation 整体脱敏——
     * 所有 observedMap value(无论 key 命名)替换为 {@code <memory>}。
     * 不依赖 key 命名前缀(模型自由命名:temperature / home / preferred_temperature 等)。
     */
    @Test
    public void redactsAllValuesWhenCapabilityIsMemoryPreference() {
        AuditRedactor redactor = new AuditRedactor(1000);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("preferred_temperature", "24");
        observed.put("temperature", "24");
        observed.put("home", "公司");
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "memory.preference.save", "已记住 preferred_temperature=24",
                observed, true, 5L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("memory.preference.save", observed), result);

        ToolObservation redacted = redactor.redact(observation);

        assertEquals(AuditRedactor.MEMORY_MESSAGE_PLACEHOLDER,
                redacted.getResult().getMessage());
        assertEquals("<memory>", redacted.getResult().getObservedState().get("preferred_temperature"));
        assertEquals("<memory>", redacted.getResult().getObservedState().get("temperature"));
        assertEquals("<memory>", redacted.getResult().getObservedState().get("home"));
    }

    /** memory.preference.get 也走全脱敏——读路径同样不该把 value 暴露给审计/UI。 */
    @Test
    public void redactsAllValuesWhenCapabilityIsMemoryPreferenceGet() {
        AuditRedactor redactor = new AuditRedactor(1000);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("preferred_temperature", "24");
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "memory.preference.get", "preferred_temperature=24",
                observed, true, 3L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("memory.preference.get", observed), result);

        ToolObservation redacted = redactor.redact(observation);

        assertEquals("<memory>", redacted.getResult().getObservedState().get("preferred_temperature"));
        assertEquals(AuditRedactor.MEMORY_MESSAGE_PLACEHOLDER, redacted.getResult().getMessage());
    }

    /**
     * 非 memory 类 capability 的 observedState 原值保留(车辆状态)。
     *
     * <p>修订:无 audit template 时 message 走 {@code redactFreeText}
     * ({@code [redacted:chars=N,sha=xxxxxxxx]}),不再保留原文 + 凭据正则——
     * 模型 / Provider 自由文本含业务 PII 时凭据正则识别不出,fail-closed 才安全。
     */
    @Test
    public void nonMemoryCapabilityKeepsObservedValuesIntact() {
        // AuditRedactor 默认已改 fail-closed UnavailableAuditDigest,
        // 测试 SHA-1 格式契约必须显式 opt-in。
        AuditRedactor redactor = new AuditRedactor(1000);
        redactor.setDigest(new Sha1AuditDigest());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("driver.temperature", 24);
        observed.put("driver.humidity", 50);
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "vehicle.climate.set_temperature", "已设置 sk-abcd1234abcd1234 含密钥",
                observed, true, 8L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("vehicle.climate.set_temperature",
                        args("zone", "driver", "temperature", 24)), result);

        ToolObservation redacted = redactor.redact(observation);

        assertTrue("第八轮 P1.2: 无 template message 应走 redactFreeText 格式, 实际="
                + redacted.getResult().getMessage(),
                redacted.getResult().getMessage().matches(
                        "^\\[redacted:chars=\\d+,sha=[0-9a-f]{8}\\]$"));
        assertEquals(24, redacted.getResult().getObservedState().get("driver.temperature"));
        assertEquals(50, redacted.getResult().getObservedState().get("driver.humidity"));
    }

    /** Policy 拒绝路径(rejectionReason)走 secret mask + 截断,保留 capabilityBlocked 标志。 */
    @Test
    public void redactsRejectionReasonPreservingCapabilityBlockedFlag() {
        AuditRedactor redactor = new AuditRedactor(1000);
        ToolCall call = new ToolCall("vehicle.adas.set_enabled", args("enabled", true));
        ToolObservation observation = ToolObservation.rejected(call,
                "R3 禁止能力 Bearer abc.def.ghi", true);

        ToolObservation redacted = redactor.redact(observation);

        assertTrue(redacted.isCapabilityBlocked());
        assertTrue(redacted.getRejectionReason().contains("***"));
        assertEquals(null, redacted.getResult());
    }

    // ===== schema-aware 业务字段脱敏 =====

    /** schema 标 sensitive 的 memory preference value 必须按 placeholder 脱敏。 */
    @Test
    public void schemaRedactsMemoryPreferenceValue() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "preferred_temperature");
        args.put("value", "24");

        Map<String, Object> redacted = redactor.redactArguments("memory.preference.save", args);

        assertEquals("<memory>", redacted.get("value"));
        assertEquals("<memory>", redacted.get("key"));
    }

    /** navigation.start_route.destination 整体替换为 <destination>,无论原值是什么。 */
    @Test
    public void schemaRedactsNavigationDestination() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("destination", "北京市某小区 5 号楼");

        Map<String, Object> redacted = redactor.redactArguments("navigation.start_route", args);

        assertEquals("<destination>", redacted.get("destination"));
    }

    /** 内置凭据 key(api_key/token/bearer 等)即使 schema 没标也要 mask,大小写不敏感。 */
    @Test
    public void builtinCredentialKeysAreMaskedEvenWithoutSchema() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("API_KEY", "sk-abcd1234abcd1234");
        args.put("Token", "abc.def.ghi");
        args.put("Authorization", "Bearer xyz");

        Map<String, Object> redacted = redactor.redactArguments("vehicle.climate.set_temperature", args);

        assertEquals("***", redacted.get("API_KEY"));
        assertEquals("***", redacted.get("Token"));
        assertEquals("***", redacted.get("Authorization"));
    }

    /** schema 未标 sensitive 且非凭据字段(zone、temperature)保留原值——可读以利诊断。 */
    @Test
    public void nonSensitiveArgsAreKeptIntact() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("zone", "driver");
        args.put("temperature", 24);

        Map<String, Object> redacted = redactor.redactArguments("vehicle.climate.set_temperature", args);

        assertEquals("driver", redacted.get("zone"));
        assertEquals(24, redacted.get("temperature"));
    }

    /** 未注入 registry 时退化为 legacy redactArguments(只跑凭据正则),保持向后兼容。 */
    @Test
    public void withoutRegistryFallsBackToCredentialOnly() {
        AuditRedactor redactor = new AuditRedactor(1000);  // 没有 registry
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("destination", "sk-abcd1234abcd1234 北京");

        Map<String, Object> redacted = redactor.redactArguments("navigation.start_route", args);

        // 没有 schema → destination 整体不脱敏,但里面的 sk-xxx 凭据被 mask
        assertEquals("*** 北京", redacted.get("destination"));
    }

    // ===== fail-closed 兜底 =====

    /**
     * 未注册的 capability(模型脑补 / 协议错误)必须 fail-closed——所有 value 替换为 ***。
     * 旧实现 fallback 到 legacy 凭据正则,导致 navigation.destination 等业务字段原值进 audit。
     */
    @Test
    public void unregisteredCapabilityMasksAllArgsFailClosed() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("destination", "北京市某小区 5 号楼");
        args.put("zone", "driver");

        Map<String, Object> redacted = redactor.redactArguments("nonexistent.capability", args);

        assertEquals("***", redacted.get("destination"));
        assertEquals("***", redacted.get("zone"));
    }

    /**
     * R3 禁止的 capability(vehicle.brake.apply 等)被 toToolDefinitions 过滤——
     * findToolDefinition 返回 null,同样必须 fail-closed。
     */
    @Test
    public void r3ProhibitedCapabilityMasksAllArgsFailClosed() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("enabled", true);
        args.put("force", "high");

        Map<String, Object> redacted = redactor.redactArguments("vehicle.brake.apply", args);

        assertEquals("***", redacted.get("enabled"));
        assertEquals("***", redacted.get("force"));
    }

    /**
     * schema 之外的额外字段(模型多塞的)违反 strict schema——
     * 即使 capability 已注册,额外字段也必须 fail-closed。
     */
    @Test
    public void extraSchemaFieldsAreMaskedFailClosed() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("zone", "driver");          // 在 schema
        args.put("temperature", 24);         // 在 schema
        args.put("user_secret", "sk-abcd1234abcd1234");  // 不在 schema,且是凭据
        args.put("extra_meta", "hidden");    // 不在 schema,非凭据

        Map<String, Object> redacted = redactor.redactArguments("vehicle.climate.set_temperature", args);

        assertEquals("driver", redacted.get("zone"));
        assertEquals(24, redacted.get("temperature"));
        assertEquals("***", redacted.get("user_secret"));
        assertEquals("***", redacted.get("extra_meta"));
    }

    // ===== ToolResult 按 AuditSchema 投影 =====

    /**
     * Provider 返回的 ToolResult.message 嵌入了真实目的地("已开始导航到 \"北京市某小区\""),
     * AuditRedactor 必须按 schema 替换为 auditMessageTemplate,真实地址不进 Trajectory / UI / 日志。
     */
    @Test
    public void schemaRedactsNavigationObservationMessage() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("navigation.destination", "北京市某小区 5 号楼");
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "navigation.start_route", "已开始导航到“北京市某小区 5 号楼”",
                observed, true, 12L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("navigation.start_route", observed), result);

        ToolObservation redacted = redactor.redact(observation);

        assertEquals("已开始导航到 <destination>", redacted.getResult().getMessage());
        assertEquals("<destination>", redacted.getResult().getObservedState().get("navigation.destination"));
    }

    /**
     * 非 memory / 非 navigation 的 capability(vehicle.climate) 没有 AuditSchema。
     *
     * <p>修订:无 schema 时 message 走 {@code redactFreeText} (fail-closed),
     * 不再保留原文——模型 / Provider 自由文本含业务 PII 时凭据正则识别不出。
     * observedState 字段值仍保留 (无 schema 时默认 redactMapRecursive,数值字段不变)。
     */
    @Test
    public void capabilityWithoutAuditSchemaKeepsDefaultRedaction() {
        // 测试 SHA-1 格式契约必须显式 opt-in(默认已是 UnavailableAuditDigest)。
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        redactor.setDigest(new Sha1AuditDigest());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("driver.temperature", 24);
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "vehicle.climate.set_temperature", "空调温度已设置",
                observed, true, 8L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("vehicle.climate.set_temperature",
                        args("zone", "driver", "temperature", 24)), result);

        ToolObservation redacted = redactor.redact(observation);

        assertTrue("第八轮 P1.2: 无 schema message 应走 redactFreeText, 实际="
                + redacted.getResult().getMessage(),
                redacted.getResult().getMessage().matches(
                        "^\\[redacted:chars=\\d+,sha=[0-9a-f]{8}\\]$"));
        assertEquals(24, redacted.getResult().getObservedState().get("driver.temperature"));
    }

    private static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    // ===== 失败 ToolResult 与未知回读字段 fail-closed =====

    /**
     * Provider 失败 message 嵌入了真实地址("导航到北京市某小区失败:网络不可用"),
     * 通用凭据正则识别不出业务值。配置了 auditFailureMessageTemplate 必须替换,
     * 真实地址不得进 Trajectory/UI/Log。
     */
    @Test
    public void schemaRedactsNavigationFailureMessageWithTemplate() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("navigation.destination", "北京市某小区 5 号楼");
        ToolResult result = new ToolResult(ToolResult.Status.EXECUTION_FAILED,
                "navigation.start_route", "导航到北京市某小区 5 号楼失败:网络不可用",
                observed, false, 15L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("navigation.start_route",
                        args("destination", "北京市某小区 5 号楼")), result);

        ToolObservation redacted = redactor.redact(observation);

        assertEquals("auditFailureMessageTemplate 必须替换失败 message",
                "导航失败,详情见 errorCode", redacted.getResult().getMessage());
        assertEquals("<destination>",
                redacted.getResult().getObservedState().get("navigation.destination"));
    }

    /**
     * capability 配置了 auditMessageTemplate 但未配置 auditFailureMessageTemplate 时,
     * 失败 message 也不能 fall through 到 redact 原文(凭据正则识别不出业务值),
     * 必须用 FAILURE_MESSAGE_FALLBACK 占位符。
     */
    @Test
    public void schemaFallbacksWhenFailureTemplateMissingButAuditSchemaDeclared() {
        // 自定义 registry:只配 auditMessageTemplate,不配 auditFailureMessageTemplate
        com.matrix.agent.task.capability.CapabilityRegistry registry =
                new com.matrix.agent.task.capability.CapabilityRegistry()
                        .register(com.matrix.agent.task.capability.CapabilityDefinition.builder(
                                "navigation.start_route",
                                com.matrix.agent.task.capability.RiskLevel.R1_LOW_RISK_WRITE)
                                .auditMessageTemplate("已开始导航到 <destination>")
                                .sensitiveObservedField("navigation.destination", "<destination>")
                                .build());
        AuditRedactor redactor = new AuditRedactor(1000, registry);
        ToolResult result = new ToolResult(ToolResult.Status.EXECUTION_FAILED,
                "navigation.start_route", "导航到我家小区失败:网络不可用",
                java.util.Collections.<String, Object>emptyMap(), false, 15L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("navigation.start_route",
                        args("destination", "我家小区")), result);

        ToolObservation redacted = redactor.redact(observation);

        assertEquals(AuditRedactor.FAILURE_MESSAGE_FALLBACK,
                redacted.getResult().getMessage());
    }

    /**
     * Provider 新增 schema 外 observedState 字段(如 navigation.requested_destination、
     * route.address)默认 mask。allowlist 只放行 navigation.status / navigation.error_code。
     */
    @Test
    public void schemaMasksUnknownObservedFieldsFailClosed() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("navigation.destination", "北京市某小区 5 号楼");  // sensitive
        observed.put("navigation.status", "OK");                       // allowlist
        observed.put("navigation.error_code", 0);                      // allowlist
        observed.put("navigation.requested_destination", "北京市某小区 5 号楼");  // schema 外
        observed.put("route.address", "北京市某小区");                            // schema 外
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "navigation.start_route", "已开始导航到北京市某小区 5 号楼",
                observed, true, 12L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("navigation.start_route",
                        args("destination", "北京市某小区 5 号楼")), result);

        ToolObservation redacted = redactor.redact(observation);

        // sensitive 字段用占位符
        assertEquals("<destination>",
                redacted.getResult().getObservedState().get("navigation.destination"));
        // allowlist 字段保留原值
        assertEquals("OK", redacted.getResult().getObservedState().get("navigation.status"));
        assertEquals(0, redacted.getResult().getObservedState().get("navigation.error_code"));
        // schema 外字段一律 mask
        assertEquals("***",
                redacted.getResult().getObservedState().get("navigation.requested_destination"));
        assertEquals("***", redacted.getResult().getObservedState().get("route.address"));
    }

    /**
     * 失败状态 + 未知 observedState 字段,业务值也不能泄漏。
     * 验证 failure template + allowlist mask 同时生效。
     */
    @Test
    public void failureWithUnknownObservedFieldsMasksBusinessValue() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("navigation.destination", "北京市某小区 5 号楼");  // sensitive
        observed.put("navigation.status", "FAILED");                   // allowlist
        observed.put("navigation.poi", "某小区");                      // schema 外(模型可能塞)
        ToolResult result = new ToolResult(ToolResult.Status.EXECUTION_FAILED,
                "navigation.start_route", "导航到北京市某小区 5 号楼失败:网络不可用",
                observed, false, 20L);
        ToolObservation observation = ToolObservation.of(
                new ToolCall("navigation.start_route",
                        args("destination", "北京市某小区 5 号楼")), result);

        ToolObservation redacted = redactor.redact(observation);

        // 失败 message 用 auditFailureMessageTemplate
        assertEquals("导航失败,详情见 errorCode", redacted.getResult().getMessage());
        // observedState fail-closed
        assertEquals("<destination>",
                redacted.getResult().getObservedState().get("navigation.destination"));
        assertEquals("FAILED", redacted.getResult().getObservedState().get("navigation.status"));
        assertEquals("***", redacted.getResult().getObservedState().get("navigation.poi"));
    }
}
