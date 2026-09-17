package com.matrix.agent.task.capability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * VerifyMethod 枚举迁移测试。
 *
 * <p>验证:
 * <ul>
 *   <li>verifyMethod() 主入口设置后 isVerificationRequired() 正确反映</li>
 *   <li>verificationRequired(true) 桥接到 verifyMethod=READBACK_FIELD</li>
 *   <li>verificationRequired(false) 桥接到 verifyMethod=NONE</li>
 *   <li>默认 verifyMethod=NONE(向后兼容)</li>
 * </ul>
 */
public final class VerifyMethodMigrationTest {

    @Test
    public void verifyMethodNoneIsNotVerificationRequired() {
        CapabilityDefinition def = CapabilityDefinition.builder("cap", RiskLevel.R0_READ_ONLY)
                .verifyMethod(VerifyMethod.NONE)
                .build();
        assertEquals(VerifyMethod.NONE, def.getVerifyMethod());
        assertFalse(def.isVerificationRequired());
    }

    @Test
    public void verifyMethodReadbackFieldIsVerificationRequired() {
        CapabilityDefinition def = CapabilityDefinition.builder("cap", RiskLevel.R1_LOW_RISK_WRITE)
                .writeOperation(true)
                .verifyMethod(VerifyMethod.READBACK_FIELD)
                .build();
        assertEquals(VerifyMethod.READBACK_FIELD, def.getVerifyMethod());
        assertTrue(def.isVerificationRequired());
    }

    @Test
    public void verifyMethodReadbackGetIsVerificationRequired() {
        CapabilityDefinition def = CapabilityDefinition.builder("cap", RiskLevel.R1_LOW_RISK_WRITE)
                .writeOperation(true)
                .verifyMethod(VerifyMethod.READBACK_GET)
                .build();
        assertEquals(VerifyMethod.READBACK_GET, def.getVerifyMethod());
        assertTrue(def.isVerificationRequired());
    }

    /** verificationRequired(true) 桥接到 READBACK_FIELD。 */
    @Test
    public void verificationRequiredTrueBridgesToReadbackField() {
        CapabilityDefinition def = CapabilityDefinition.builder("cap", RiskLevel.R1_LOW_RISK_WRITE)
                .writeOperation(true)
                .verificationRequired(true)
                .build();
        assertEquals("V0.4.0 true → READBACK_FIELD",
                VerifyMethod.READBACK_FIELD, def.getVerifyMethod());
        assertTrue(def.isVerificationRequired());
    }

    /** verificationRequired(false) 桥接到 NONE。 */
    @Test
    public void verificationRequiredFalseBridgesToNone() {
        CapabilityDefinition def = CapabilityDefinition.builder("cap", RiskLevel.R0_READ_ONLY)
                .verificationRequired(false)
                .build();
        assertEquals(VerifyMethod.NONE, def.getVerifyMethod());
        assertFalse(def.isVerificationRequired());
    }

    /** 默认(未调用任一入口)verifyMethod=NONE。 */
    @Test
    public void defaultVerifyMethodIsNone() {
        CapabilityDefinition def = CapabilityDefinition.builder("cap", RiskLevel.R0_READ_ONLY)
                .build();
        assertEquals(VerifyMethod.NONE, def.getVerifyMethod());
        assertFalse(def.isVerificationRequired());
    }

    /** demo registry 的 climate 写操作桥接到 READBACK_FIELD。 */
    @Test
    public void demoRegistryClimateUsesReadbackField() {
        CapabilityDefinition climate = CapabilityRegistry.createDemoRegistry()
                .find("vehicle.climate.set_temperature");
        assertEquals(VerifyMethod.READBACK_FIELD, climate.getVerifyMethod());
        assertTrue(climate.isVerificationRequired());
    }
}
