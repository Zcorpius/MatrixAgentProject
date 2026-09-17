package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityRegistry;

/**
 * AuditRedactor 业务 PII key 扩充测试。
 *
 * <p>验证 {@link com.matrix.agent.data.SensitiveKeys#BUILTIN_PII_KEYS} 整合到
 * {@link AuditRedactor#BUILTIN_CREDENTIAL_KEYS} 后的行为:
 * <ul>
 *   <li>业务 PII key(home_address / contact_phone / id_card 等)命中 → value 替换为 {@code ***};</li>
 *   <li>key 本身是元数据,不 mask;</li>
 *   <li>大小写不敏感匹配(HOME_ADDRESS 同样命中)。</li>
 * </ul>
 *
 * <p>memory.semantic.* 的 value 已通过 schema sensitivePlaceholders → {@code <memory>},
 * 本测试聚焦**非 memory.capability** 路径(如未来 user.profile.update)的兜底防御。
 */
public final class AuditRedactorPiiKeyTest {

    @Test
    public void homeAddressValueIsMaskedAsPii() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("home_address", "北京市朝阳区");

        Map<String, Object> redacted = redactor.redactArguments(
                "vehicle.climate.set_temperature", args);

        assertEquals("***", redacted.get("home_address"));
    }

    @Test
    public void contactPhoneValueIsMaskedAsPii() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("contact_phone", "13800138000");

        Map<String, Object> redacted = redactor.redactArguments(
                "vehicle.climate.set_temperature", args);

        assertEquals("***", redacted.get("contact_phone"));
    }

    @Test
    public void idCardValueIsMaskedAsPii() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id_card", "110101199001011234");

        Map<String, Object> redacted = redactor.redactArguments(
                "vehicle.climate.set_temperature", args);

        assertEquals("***", redacted.get("id_card"));
    }

    @Test
    public void piiKeyMatchingIsCaseInsensitive() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("HOME_ADDRESS", "北京");

        Map<String, Object> redacted = redactor.redactArguments(
                "vehicle.climate.set_temperature", args);

        assertEquals("大小写不敏感:HOME_ADDRESS 同样 mask",
                "***", redacted.get("HOME_ADDRESS"));
    }

    @Test
    public void nonPiiArgIsKeptIntact() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("zone", "driver");
        args.put("temperature", 24);

        Map<String, Object> redacted = redactor.redactArguments(
                "vehicle.climate.set_temperature", args);

        assertEquals("driver", redacted.get("zone"));
        assertEquals(24, redacted.get("temperature"));
    }

    @Test
    public void piiKeyItselfIsNotMasked() {
        AuditRedactor redactor = new AuditRedactor(1000, CapabilityRegistry.createDemoRegistry());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("home_address", "北京市朝阳区");

        Map<String, Object> redacted = redactor.redactArguments(
                "vehicle.climate.set_temperature", args);

        // key 本身保留(是元数据),只 value 替换为 ***
        assertTrue("key 必须保留", redacted.containsKey("home_address"));
        assertFalse("不应有 ***-key", redacted.containsKey("***"));
        assertEquals("***", redacted.get("home_address"));
    }
}
