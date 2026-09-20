package com.matrix.agent.voice;

import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话绑定预滚缓冲（设计文档 §7.3/阶段 C-3）。
 *
 * <p>解决 KWS stop → command ASR start 间隙丢首字：采音线程持续 append（byte[] PCM，
 * 16kHz mono 16-bit LE），唤醒命中时 {@link #capture}，ASR 启动时 {@link #consume}
 * 注入——只有一次交接令牌能消费，多轮唤醒不会互串。
 */
public final class AudioPrerollBuffer {

    private static final String TAG = "MatrixAgent";
    private static final int BYTES_PER_SAMPLE = 2; // 16-bit mono

    private final byte[] ring;
    private final int capacityBytes;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong generation = new AtomicLong(0);

    private int writePos;
    private int filled;
    private byte[] captured;
    private long capturedGeneration = -1;

    /** @param capacityMs 环形缓冲容量（毫秒），设计值 2500ms。 */
    public AudioPrerollBuffer(int capacityMs) {
        // 16kHz * 2 bytes/sample * seconds
        this.capacityBytes = 16000 * BYTES_PER_SAMPLE * capacityMs / 1000;
        this.ring = new byte[capacityBytes];
    }

    /** 采音线程每帧调用：写入环形缓冲（覆盖最旧）。 */
    public void append(byte[] pcm, int length) {
        if (pcm == null || length <= 0) return;
        lock.lock();
        try {
            int n = Math.min(length, pcm.length);
            for (int i = 0; i < n; i++) {
                ring[writePos] = pcm[i];
                writePos = (writePos + 1) % capacityBytes;
                if (filled < capacityBytes) filled++;
            }
        } finally {
            lock.unlock();
        }
    }

    /** 唤醒命中时调用：截取最近 windowMs 毫秒音频。 */
    public void capture(int windowMs, long wakeGeneration) {
        lock.lock();
        try {
            int requestedBytes = Math.min(
                    16000 * BYTES_PER_SAMPLE * windowMs / 1000, filled);
            if (requestedBytes <= 0) return;
            byte[] snapshot = new byte[requestedBytes];
            int start = ((writePos - requestedBytes) % capacityBytes + capacityBytes)
                    % capacityBytes;
            for (int i = 0; i < requestedBytes; i++) {
                snapshot[i] = ring[(start + i) % capacityBytes];
            }
            captured = snapshot;
            capturedGeneration = wakeGeneration;
            Log.d(TAG, "[Preroll] capture gen=" + wakeGeneration
                    + " bytes=" + requestedBytes);
        } finally {
            lock.unlock();
        }
    }

    /** ASR 启动时调用：消费预滚（单次）。代次不匹配返回 null。 */
    public byte[] consume(long expectedGeneration) {
        lock.lock();
        try {
            if (captured == null || capturedGeneration != expectedGeneration) return null;
            byte[] result = captured;
            captured = null;
            capturedGeneration = -1;
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            writePos = 0; filled = 0; captured = null; capturedGeneration = -1;
            generation.set(0);
        } finally {
            lock.unlock();
        }
    }

    public long nextGeneration() { return generation.incrementAndGet(); }
    public boolean hasCaptured(long gen) {
        lock.lock();
        try { return captured != null && capturedGeneration == gen; }
        finally { lock.unlock(); }
    }
}
