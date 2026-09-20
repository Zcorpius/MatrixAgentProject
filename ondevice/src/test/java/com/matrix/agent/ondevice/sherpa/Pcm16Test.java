package com.matrix.agent.ondevice.sherpa;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** PCM16 LE → float 转换（sherpa 流式 API 输入表示）。 */
public final class Pcm16Test {

    @Test
    public void convertsLittleEndianSamplesToNormalizedFloats() {
        byte[] pcm = {
                0x00, 0x00, // 0
                0x00, (byte) 0x80, // -32768 → -1.0
                (byte) 0xFF, 0x7F, // 32767 → ~0.99997
                0x00, 0x40, // 16384 → 0.5
        };
        float[] out = Pcm16.toFloats(pcm, pcm.length);
        assertEquals(4, out.length);
        assertEquals(0f, out[0], 0f);
        assertEquals(-1f, out[1], 0f);
        assertEquals(32767f / 32768f, out[2], 1e-6f);
        assertEquals(0.5f, out[3], 0f);
    }

    @Test
    public void ignoresTrailingOddByte() {
        byte[] pcm = {0x01, 0x00, 0x02, 0x00, 0x03};
        float[] out = Pcm16.toFloats(pcm, pcm.length);
        assertEquals(2, out.length);
        assertArrayEquals(new float[]{1f / 32768f, 2f / 32768f}, out, 0f);
    }
}
