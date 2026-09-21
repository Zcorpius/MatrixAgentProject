package com.matrix.agent.api.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 线格式契约测试：Host 发射端与 Launcher 时间线编译器共享同一 token 语义，
 * 此处锁定解析边界（记号边界、前缀键不误配、自由文本尾部不参与结构化解析）。
 */
public final class DebugTracePayloadsTest {

    @Test
    public void tokenBuildsKeyEqualsValue() {
        assertEquals("cap=climate.set", DebugTracePayloads.capability("climate.set"));
        assertEquals("allowed=false", DebugTracePayloads.token(
                DebugTracePayloads.KEY_ALLOWED, false));
        assertEquals("status=SUCCESS", DebugTracePayloads.token(
                DebugTracePayloads.KEY_STATUS, "SUCCESS"));
    }

    @Test
    public void wordValueReadsContractedKeysFromHostShapedPayloads() {
        String policyPayload = DebugTracePayloads.capability("system.media.set_volume")
                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_ALLOWED, true)
                + " reason=写操作需要明确目标";
        assertEquals("system.media.set_volume",
                DebugTracePayloads.wordValue(policyPayload, DebugTracePayloads.KEY_CAPABILITY));
        assertEquals("true",
                DebugTracePayloads.wordValue(policyPayload, DebugTracePayloads.KEY_ALLOWED));

        String verifiedPayload = DebugTracePayloads.capability("climate.set")
                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_STATUS, "SUCCESS")
                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_VERIFIED, true)
                + " durationMs=5 observedKeys={level=java.lang.Integer}";
        assertEquals("SUCCESS",
                DebugTracePayloads.wordValue(verifiedPayload, DebugTracePayloads.KEY_STATUS));
        assertEquals("true",
                DebugTracePayloads.wordValue(verifiedPayload, DebugTracePayloads.KEY_VERIFIED));
    }

    @Test
    public void wordValueOnlyMatchesAtTokenBoundaries() {
        // "cap" 不得误匹配更长的键名 "capability"；值中再次出现的 "cap=" 也不是记号边界。
        assertNull(DebugTracePayloads.wordValue("capability=x cap", "cap"));
        assertEquals("real", DebugTracePayloads.wordValue("prefix cap=real capability=y", "cap"));
        assertNull(DebugTracePayloads.wordValue("notcap=real", "cap"));
        assertNull(DebugTracePayloads.wordValue("cap", "cap"));
    }

    @Test
    public void flagValueRejectsNonBooleanWordsInsteadOfGuessing() {
        assertEquals(Boolean.TRUE, DebugTracePayloads.flagValue(
                "allowed=true", DebugTracePayloads.KEY_ALLOWED));
        assertEquals(Boolean.FALSE, DebugTracePayloads.flagValue(
                "verified=false", DebugTracePayloads.KEY_VERIFIED));
        assertNull(DebugTracePayloads.flagValue("allowed=maybe", DebugTracePayloads.KEY_ALLOWED));
        assertNull(DebugTracePayloads.flagValue("reason=allowed=true", DebugTracePayloads.KEY_ALLOWED));
        assertNull(DebugTracePayloads.flagValue(null, DebugTracePayloads.KEY_ALLOWED));
    }

    @Test
    public void missingKeyAndNullPayloadReturnNull() {
        assertNull(DebugTracePayloads.wordValue("iter=0 finish=TOOL_CALLS",
                DebugTracePayloads.KEY_CAPABILITY));
        assertNull(DebugTracePayloads.wordValue(null, DebugTracePayloads.KEY_CAPABILITY));
        assertNull(DebugTracePayloads.wordValue("cap=x", null));
    }
}
