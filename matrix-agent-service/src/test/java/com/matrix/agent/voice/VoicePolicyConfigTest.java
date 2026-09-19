package com.matrix.agent.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** {@link VoicePolicyConfig} 默认值与参数校验。 */
public final class VoicePolicyConfigTest {

    @Test
    public void defaults_hasDocumentedValues() {
        VoicePolicyConfig p = VoicePolicyConfig.defaults();
        assertEquals(3000L, p.speechStartMs());
        assertEquals(15000L, p.maxSpeechMs());
        assertEquals(2000L, p.finalWaitMs());
        assertEquals(1, p.maxReprompt());
        assertEquals(0.5f, p.minConfidence(), 0f);
        assertEquals(1, p.minTextLength());
        assertEquals(3000L, p.ttsReadyMs());
        assertEquals(200L, p.ttsPerCharMs());
        assertEquals(30000L, p.ttsMaxSpeakMs());
        // barge-in
        assertEquals(1500, p.bargeInRmsThreshold());
        assertEquals(5, p.bargeInHoldFrames());
        assertFalse(p.bargeInForceHalfDuplex());
    }

    @Test
    public void sixArgConstructor_usesDefaultTtsAndBargeIn() {
        VoicePolicyConfig p = new VoicePolicyConfig(1, 1, 1, 0, 0f, 1);
        assertEquals(3000L, p.ttsReadyMs());
        assertEquals(1500, p.bargeInRmsThreshold());
        assertEquals(5, p.bargeInHoldFrames());
    }

    @Test
    public void nineArgConstructor_usesDefaultBargeIn() {
        VoicePolicyConfig p = new VoicePolicyConfig(1, 1, 1, 0, 0f, 1, 1, 1, 1);
        assertEquals(1500, p.bargeInRmsThreshold());
        assertEquals(5, p.bargeInHoldFrames());
        assertFalse(p.bargeInForceHalfDuplex());
    }

    @Test
    public void negativeTimeout_throws() {
        try { new VoicePolicyConfig(-1, 1, 1, 0, 0f, 1); fail("speechStartMs<0 应抛"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void invalidConfidence_throws() {
        try { new VoicePolicyConfig(1, 1, 1, 0, 1.5f, 1); fail("minConfidence>1 应抛"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void zeroMinTextLength_throws() {
        try { new VoicePolicyConfig(1, 1, 1, 0, 0f, 0); fail("minTextLength<1 应抛"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void negativeMaxReprompt_throws() {
        try { new VoicePolicyConfig(1, 1, 1, -1, 0f, 1); fail("maxReprompt<0 应抛"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void negativeTtsReady_throws() {
        try { new VoicePolicyConfig(1, 1, 1, 0, 0f, 1, -1, 1, 1); fail("ttsReadyMs<0 应抛"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void negativeBargeInRms_throws() {
        try { new VoicePolicyConfig(1, 1, 1, 0, 0f, 1, 1, 1, 1, -1, 5, false); fail("bargeInRmsThreshold<0 应抛"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void zeroHoldFrames_throws() {
        try { new VoicePolicyConfig(1, 1, 1, 0, 0f, 1, 1, 1, 1, 1500, 0, false); fail("bargeInHoldFrames<1 应抛"); }
        catch (IllegalArgumentException expected) { }
    }

    @Test
    public void twelveArgConstructor_forceHalfDuplex() {
        VoicePolicyConfig p = new VoicePolicyConfig(1, 1, 1, 0, 0f, 1, 1, 1, 1, 1200, 4, true);
        assertEquals(1200, p.bargeInRmsThreshold());
        assertEquals(4, p.bargeInHoldFrames());
        assertTrue(p.bargeInForceHalfDuplex());
    }
}
