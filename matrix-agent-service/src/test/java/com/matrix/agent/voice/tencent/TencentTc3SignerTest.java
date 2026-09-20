package com.matrix.agent.voice.tencent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Locks the TC3 signed-header contract to Tencent's documented content-type;host form. */
public final class TencentTc3SignerTest {
    @Test public void sign_isDeterministic_andDoesNotExposeSecretKey() throws Exception {
        TencentTc3Signer.SignedHeaders first = TencentTc3Signer.sign(
                "AKIDabcdefghijklmnopqrstuv", "aVerySecretKey_1234567890", "{\"Text\":\"你好\"}",
                1_700_000_000L);
        TencentTc3Signer.SignedHeaders second = TencentTc3Signer.sign(
                "AKIDabcdefghijklmnopqrstuv", "aVerySecretKey_1234567890", "{\"Text\":\"你好\"}",
                1_700_000_000L);
        assertEquals(first.authorization, second.authorization);
        assertTrue(first.authorization.contains("SignedHeaders=content-type;host"));
        assertTrue(first.authorization.contains("/2023-11-14/tts/tc3_request"));
        assertFalse(first.authorization.contains("aVerySecretKey_1234567890"));
        assertEquals("1700000000", first.timestamp);
    }
}
