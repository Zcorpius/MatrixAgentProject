package com.matrix.agent.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * {@link FinalTranscript} 值对象不变式。
 *
 * <p>重点守护"未知置信度归零"——下游绝不能读到伪装的 1.0。
 */
public final class FinalTranscriptTest {

    @Test
    public void confidenceUnavailable_normalizesConfidenceToZero() {
        FinalTranscript t = new FinalTranscript("开空调", "zh-CN", 0.9f, false);
        assertFalse(t.confidenceAvailable());
        // 未知置信度归零,杜绝下游读到伪造的 0.9
        assertEquals(0f, t.confidence(), 0f);
    }

    @Test
    public void confidenceAvailable_keepsValue() {
        FinalTranscript t = new FinalTranscript("开空调", "zh-CN", 0.8f, true);
        assertEquals(0.8f, t.confidence(), 0f);
    }

    @Test
    public void emptyText_allowed() {
        // 空 final 由 Controller 转写校验处理(规范化),值对象层允许空串(仅禁 null)
        FinalTranscript t = new FinalTranscript("", "zh-CN", 0f, false);
        assertEquals("", t.text());
    }

    @Test
    public void invalidArgs_throw() {
        try {
            new FinalTranscript(null, "zh-CN", 0f, false);
            fail("text=null 应抛");
        } catch (IllegalArgumentException expected) { }
        try {
            new FinalTranscript("x", "", 0f, false);
            fail("空 languageTag 应抛");
        } catch (IllegalArgumentException expected) { }
        try {
            new FinalTranscript("x", "zh-CN", -0.1f, true);
            fail("confidence<0 应抛");
        } catch (IllegalArgumentException expected) { }
        try {
            new FinalTranscript("x", "zh-CN", 1.5f, true);
            fail("confidence>1 应抛");
        } catch (IllegalArgumentException expected) { }
        try {
            new FinalTranscript("x", "zh-CN", Float.NaN, true);
            fail("NaN confidence 应抛");
        } catch (IllegalArgumentException expected) { }
    }
}
