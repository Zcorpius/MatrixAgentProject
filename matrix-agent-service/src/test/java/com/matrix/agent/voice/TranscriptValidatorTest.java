package com.matrix.agent.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link TranscriptValidator} 裁决。
 *
 * <p>重点守护 fail-closed:空/过短/低置信 REJECT;confidenceAvailable=false(置信度未知)同样 REJECT(不送 Agent/车控)。
 */
public final class TranscriptValidatorTest {

    private final TranscriptValidator validator = new TranscriptValidator();

    @Test
    public void empty_rejected() {
        TranscriptValidator.Verdict v = validator.validate(
                new FinalTranscript("", "zh-CN", 0f, false), VoicePolicyConfig.defaults(), 0);
        assertFalse(v.accepted());
        assertEquals("EMPTY", v.reason());
    }

    @Test
    public void whitespaceOnly_rejectedAsEmpty() {
        TranscriptValidator.Verdict v = validator.validate(
                new FinalTranscript("   ", "zh-CN", 0f, false), VoicePolicyConfig.defaults(), 0);
        assertFalse(v.accepted());
        assertEquals("EMPTY", v.reason());
    }

    @Test
    public void tooShort_rejected() {
        VoicePolicyConfig policy = new VoicePolicyConfig(1, 1, 1, 0, 0f, 2); // minTextLength=2
        TranscriptValidator.Verdict v = validator.validate(
                new FinalTranscript("嗯", "zh-CN", 0f, false), policy, 0);
        assertFalse(v.accepted());
        assertEquals("TOO_SHORT", v.reason());
    }

    @Test
    public void lowConfidence_rejected() {
        TranscriptValidator.Verdict v = validator.validate(
                new FinalTranscript("开空调", "zh-CN", 0.2f, true), VoicePolicyConfig.defaults(), 0);
        assertFalse(v.accepted());
        assertEquals("LOW_CONFIDENCE", v.reason());
    }

    @Test
    public void confidenceUnavailable_failClosed_rejected() {
        // P1-4: confidenceAvailable=false(未知置信度)→REJECT(fail-closed),不送 Agent/车控
        TranscriptValidator.Verdict v = validator.validate(
                new FinalTranscript("开空调", "zh-CN", 0f, false), VoicePolicyConfig.defaults(), 0);
        assertFalse(v.accepted());
        assertEquals("CONFIDENCE_UNAVAILABLE", v.reason());
    }

    @Test
    public void highConfidence_accepted() {
        TranscriptValidator.Verdict v = validator.validate(
                new FinalTranscript("开空调", "zh-CN", 0.9f, true), VoicePolicyConfig.defaults(), 0);
        assertTrue(v.accepted());
    }

    @Test
    public void unsupportedLanguage_rejected() {
        TranscriptValidator.Verdict v = validator.validate(
                new FinalTranscript("开空调", "en-US", 0.9f, true), VoicePolicyConfig.defaults(), 0);
        assertFalse(v.accepted());
        assertEquals("UNSUPPORTED_LANGUAGE", v.reason());
    }

    @Test
    public void targetSeatInCommand_accepted() {
        // 命令提及目标座位(给副驾开空调)是合法能力参数;身份来自可信上下文,转写层不拒(文档 §15)。
        TranscriptValidator.Verdict v = validator.validate(
                new FinalTranscript("给副驾打开空调", "zh-CN", 0.9f, true),
                VoicePolicyConfig.defaults(), 0);
        assertTrue(v.accepted());
    }

}
