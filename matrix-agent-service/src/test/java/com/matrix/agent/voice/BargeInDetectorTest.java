package com.matrix.agent.voice;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * {@link BargeInDetector} RMS 连续帧判定 + AEC 半双工降级。
 *
 * <p>{@link #frame(short, int)} 构造每样本=amplitude 的 PCM(RMS=|amplitude|),确定性可测。
 */
public final class BargeInDetectorTest {

    /** threshold/holdFrames 可调,其余默认。 */
    private static VoicePolicyConfig policy(int threshold, int holdFrames, boolean forceHalfDuplex) {
        return new VoicePolicyConfig(1, 1, 1, 0, 0f, 1, 1, 1, 1, threshold, holdFrames, forceHalfDuplex);
    }

    private static byte[] frame(short amplitude, int samples) {
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            pcm[i * 2] = (byte) (amplitude & 0xFF);
            pcm[i * 2 + 1] = (byte) ((amplitude >> 8) & 0xFF);
        }
        return pcm;
    }

    @Test
    public void consecutiveOverThreshold_triggersBargeIn() {
        int[] count = {0};
        BargeInDetector d = new BargeInDetector(policy(1500, 3, false), () -> count[0]++);
        d.setAecAvailable(true);
        byte[] loud = frame((short) 2000, 100);
        d.feed(loud, loud.length);
        d.feed(loud, loud.length);
        assertEquals("2 帧不足 holdFrames=3,不应触发", 0, count[0]);
        d.feed(loud, loud.length); // 第 3 帧 → 触发
        assertEquals(1, count[0]);
    }

    @Test
    public void belowThreshold_noTrigger() {
        int[] count = {0};
        BargeInDetector d = new BargeInDetector(policy(1500, 2, false), () -> count[0]++);
        d.setAecAvailable(true);
        byte[] quiet = frame((short) 500, 100);
        for (int i = 0; i < 10; i++) d.feed(quiet, quiet.length);
        assertEquals(0, count[0]);
    }

    @Test
    public void dropBelow_resetsCounter() {
        int[] count = {0};
        BargeInDetector d = new BargeInDetector(policy(1500, 3, false), () -> count[0]++);
        d.setAecAvailable(true);
        byte[] loud = frame((short) 2000, 100);
        byte[] quiet = frame((short) 500, 100);
        d.feed(loud, loud.length);
        d.feed(loud, loud.length);  // 2 帧
        d.feed(quiet, quiet.length); // 回落 → reset
        d.feed(loud, loud.length);  // 1 帧(重计)
        d.feed(loud, loud.length);  // 2 帧
        assertEquals("回落后应重新计满,未触发", 0, count[0]);
        d.feed(loud, loud.length);  // 3 帧 → 触发
        assertEquals(1, count[0]);
    }

    @Test
    public void triggerResetsCounter_noDoubleFire() {
        int[] count = {0};
        BargeInDetector d = new BargeInDetector(policy(1500, 2, false), () -> count[0]++);
        d.setAecAvailable(true);
        byte[] loud = frame((short) 2000, 100);
        d.feed(loud, loud.length);
        d.feed(loud, loud.length);  // 触发,count=1,清零
        d.feed(loud, loud.length);  // 触发后第 1 帧
        d.feed(loud, loud.length);  // 第 2 帧 → 再触发
        assertEquals(2, count[0]);
    }

    @Test
    public void aecUnavailable_halfDuplex_noTrigger() {
        int[] count = {0};
        BargeInDetector d = new BargeInDetector(policy(1500, 2, false), () -> count[0]++);
        d.setAecAvailable(false); // 半双工
        byte[] loud = frame((short) 2000, 100);
        for (int i = 0; i < 10; i++) d.feed(loud, loud.length);
        assertEquals("AEC 不可用应半双工不触发", 0, count[0]);
    }

    @Test
    public void forceHalfDuplex_noTrigger() {
        int[] count = {0};
        BargeInDetector d = new BargeInDetector(policy(1500, 2, true), () -> count[0]++);
        d.setAecAvailable(true); // AEC 可用但强制半双工
        byte[] loud = frame((short) 2000, 100);
        for (int i = 0; i < 10; i++) d.feed(loud, loud.length);
        assertEquals("forceHalfDuplex 应不触发", 0, count[0]);
    }

    @Test
    public void reset_clearsCounter() {
        int[] count = {0};
        BargeInDetector d = new BargeInDetector(policy(1500, 3, false), () -> count[0]++);
        d.setAecAvailable(true);
        byte[] loud = frame((short) 2000, 100);
        d.feed(loud, loud.length);
        d.feed(loud, loud.length); // 2 帧
        d.reset();
        d.feed(loud, loud.length); // 1 帧(重计)
        d.feed(loud, loud.length); // 2 帧
        assertEquals("reset 后应重新计满", 0, count[0]);
        d.feed(loud, loud.length); // 3 帧 → 触发
        assertEquals(1, count[0]);
    }
}
