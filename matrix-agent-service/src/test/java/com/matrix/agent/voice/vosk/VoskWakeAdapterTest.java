package com.matrix.agent.voice.vosk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 唤醒词文本匹配不依赖 native recognizer，避免词表回归。 */
public final class VoskWakeAdapterTest {

    @Test
    public void hiMatrix_isPrimaryWakePhrase() {
        assertTrue(VoskWakeAdapter.isWakePhrase("hi matrix"));
        assertTrue(VoskWakeAdapter.isWakePhrase("  HI   MATRIX  "));
    }

    @Test
    public void heyMatrix_isRetainedAsCompatibilityWakePhrase() {
        assertTrue(VoskWakeAdapter.isWakePhrase("hey matrix"));
    }

    @Test
    public void incompleteOrEmbeddedText_doesNotWake() {
        assertFalse(VoskWakeAdapter.isWakePhrase("matrix"));
        assertFalse(VoskWakeAdapter.isWakePhrase("say hi matrix please"));
        assertFalse(VoskWakeAdapter.isWakePhrase(null));
    }
}
