package com.matrix.agent.task.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * ExplicitIntentConstraints 回归。
 *
 * <p>副驾驶位 / 主副驾同时 / 驾驶位 单独出现。
 * <p>多目标 / 否定冲突不再降级为 UNSPECIFIED。
 */
public final class ExplicitIntentConstraintsTest {
    @Test
    public void emptyTextReturnsUnspecified() {
        assertEquals(ExplicitIntentConstraints.IntentType.UNSPECIFIED,
                ExplicitIntentConstraints.extractFrom(null).getIntentType());
        assertEquals(ExplicitIntentConstraints.IntentType.UNSPECIFIED,
                ExplicitIntentConstraints.extractFrom("").getIntentType());
        assertEquals(ExplicitIntentConstraints.IntentType.UNSPECIFIED,
                ExplicitIntentConstraints.extractFrom("把温度调高").getIntentType());
    }

    @Test
    public void driverKeywordsReturnSingleTargetDriver() {
        ExplicitIntentConstraints r1 = ExplicitIntentConstraints.extractFrom("把主驾温度调到24度");
        assertEquals(ExplicitIntentConstraints.IntentType.SINGLE_TARGET, r1.getIntentType());
        assertEquals(VehicleZone.DRIVER, r1.getExplicitZone());
        assertTrue(r1.hasExplicitZone());
        assertFalse(r1.isBlocked());

        ExplicitIntentConstraints r2 = ExplicitIntentConstraints.extractFrom("驾驶位太冷了");
        assertEquals(VehicleZone.DRIVER, r2.getExplicitZone());
    }

    @Test
    public void passengerShortKeywordReturnsSingleTargetPassenger() {
        ExplicitIntentConstraints r = ExplicitIntentConstraints.extractFrom("把副驾温度调到24度");
        assertEquals(ExplicitIntentConstraints.IntentType.SINGLE_TARGET, r.getIntentType());
        assertEquals(VehicleZone.PASSENGER, r.getExplicitZone());
        assertFalse(r.isBlocked());
    }

    /** 关键回归:"副驾驶位" 不再被错误识别为 DRIVER。 */
    @Test
    public void passengerFullKeywordDoesNotOverlapWithDriver() {
        ExplicitIntentConstraints r1 = ExplicitIntentConstraints.extractFrom("把副驾驶位温度调到24度");
        assertEquals(VehicleZone.PASSENGER, r1.getExplicitZone());

        ExplicitIntentConstraints r2 = ExplicitIntentConstraints.extractFrom("副驾驶空调关小一点");
        assertEquals(VehicleZone.PASSENGER, r2.getExplicitZone());
    }

    /** 同时提到主驾和副驾(都肯定) → MULTI_TARGET fail-closed。 */
    @Test
    public void bothZonesAffirmedIsMultiTargetBlocked() {
        ExplicitIntentConstraints r1 = ExplicitIntentConstraints.extractFrom("主驾和副驾都调到24度");
        assertEquals(ExplicitIntentConstraints.IntentType.MULTI_TARGET, r1.getIntentType());
        assertTrue("MULTI_TARGET 必须 fail-closed", r1.isBlocked());

        ExplicitIntentConstraints r2 = ExplicitIntentConstraints.extractFrom("主驾跟副驾温度都设为25");
        assertEquals(ExplicitIntentConstraints.IntentType.MULTI_TARGET, r2.getIntentType());
        assertTrue(r2.isBlocked());
    }

    /** "不要 X 只 Y" 否定 X 肯定 Y → SINGLE_TARGET=Y。 */
    @Test
    public void negateDriverAffirmPassengerYieldsSinglePassenger() {
        ExplicitIntentConstraints r = ExplicitIntentConstraints.extractFrom("不要调主驾,只调副驾");
        assertEquals(ExplicitIntentConstraints.IntentType.SINGLE_TARGET, r.getIntentType());
        assertEquals(VehicleZone.PASSENGER, r.getExplicitZone());
        assertFalse(r.isBlocked());
    }

    /** "不要 X 只 Y" 反向 → SINGLE_TARGET=X。 */
    @Test
    public void negatePassengerAffirmDriverYieldsSingleDriver() {
        ExplicitIntentConstraints r = ExplicitIntentConstraints.extractFrom("别调副驾,只调主驾");
        assertEquals(ExplicitIntentConstraints.IntentType.SINGLE_TARGET, r.getIntentType());
        assertEquals(VehicleZone.DRIVER, r.getExplicitZone());
    }

    /** 单 zone + 否定 + 无肯定 → CONFLICT fail-closed。 */
    @Test
    public void negatedOnlyZoneIsConflictBlocked() {
        ExplicitIntentConstraints r = ExplicitIntentConstraints.extractFrom("不要调主驾");
        assertEquals(ExplicitIntentConstraints.IntentType.CONFLICT, r.getIntentType());
        assertTrue("CONFLICT 必须 fail-closed", r.isBlocked());
    }

    /** 两个 zone 都被否定 → CONFLICT。 */
    @Test
    public void bothZonesNegatedIsConflictBlocked() {
        ExplicitIntentConstraints r = ExplicitIntentConstraints.extractFrom("不要调主驾和副驾");
        assertEquals(ExplicitIntentConstraints.IntentType.CONFLICT, r.getIntentType());
        assertTrue(r.isBlocked());
    }
}
