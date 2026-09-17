package com.matrix.agent.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * SensitiveKeys 共享 denylist 单元测试。
 *
 * <p>验证 {@link SensitiveKeys#isPiiKey(String)} 的核心契约:
 * <ul>
 *   <li>已知 PII key(home_address / contact_phone / id_card 等)→ true;</li>
 *   <li>大小写不敏感(HOME_ADDRESS 同样命中);</li>
 *   <li>非 PII key(preferred_temperature / preferred_seat_level 等)→ false;</li>
 *   <li>null 安全。</li>
 * </ul>
 */
public final class SensitiveKeysTest {

    @Test
    public void knownPiiKeysAreRecognized() {
        assertTrue("home_address", SensitiveKeys.isPiiKey("home_address"));
        assertTrue("work_address", SensitiveKeys.isPiiKey("work_address"));
        assertTrue("contact_address", SensitiveKeys.isPiiKey("contact_address"));
        assertTrue("contact_phone", SensitiveKeys.isPiiKey("contact_phone"));
        assertTrue("contact_email", SensitiveKeys.isPiiKey("contact_email"));
        assertTrue("id_card", SensitiveKeys.isPiiKey("id_card"));
        assertTrue("passport_no", SensitiveKeys.isPiiKey("passport_no"));
        assertTrue("credit_card", SensitiveKeys.isPiiKey("credit_card"));
        assertTrue("bank_account", SensitiveKeys.isPiiKey("bank_account"));
        assertTrue("medical_condition", SensitiveKeys.isPiiKey("medical_condition"));
        assertTrue("allergy_detail", SensitiveKeys.isPiiKey("allergy_detail"));
    }

    @Test
    public void matchingIsCaseInsensitive() {
        assertTrue("HOME_ADDRESS", SensitiveKeys.isPiiKey("HOME_ADDRESS"));
        assertTrue("Home_Address", SensitiveKeys.isPiiKey("Home_Address"));
        assertTrue("ID_CARD", SensitiveKeys.isPiiKey("ID_CARD"));
    }

    @Test
    public void nonPiiKeysReturnFalse() {
        assertFalse("preferred_temperature 是偏好,非 PII",
                SensitiveKeys.isPiiKey("preferred_temperature"));
        assertFalse("preferred_seat_level 是偏好",
                SensitiveKeys.isPiiKey("preferred_seat_level"));
        assertFalse("zone", SensitiveKeys.isPiiKey("zone"));
        assertFalse("temperature", SensitiveKeys.isPiiKey("temperature"));
        assertFalse("fact.daughter_name 当前不在 denylist",
                SensitiveKeys.isPiiKey("fact.daughter_name"));
    }

    @Test
    public void nullAndEmptyReturnFalse() {
        assertFalse("null", SensitiveKeys.isPiiKey(null));
        assertFalse("empty", SensitiveKeys.isPiiKey(""));
    }

    @Test
    public void builtinPiiKeysSetIsImmutable() {
        // 即使业务方拿到 Set 引用也不能改
        try {
            SensitiveKeys.BUILTIN_PII_KEYS.add("new_key");
            assertFalse("BUILTIN_PII_KEYS 应不可变", true);
        } catch (UnsupportedOperationException expected) {
            assertTrue("add 抛 UnsupportedOperationException", true);
        }
    }
}
