package com.matrix.agent.ondevice.mnn;

import com.matrix.agent.ondevice.MnnLoadOptions;

import java.io.File;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MNN LLM 会话封装（Kotlin → Java 重写，对应 Operit {@code MNNLlmSession.kt}）。
 *
 * <p>并发模型：{@code enter/exit} 用同一 lock 维护 {@code activeCalls} 计数；{@link #release()} 先标记
 * released、调 {@code nativeCancel} 中断在跑的，再等 {@code activeCalls==0}（在途 native 调用返回），
 * 最后 {@code nativeReleaseLlm}——避免 native 未返回时 destroy 造成 use-after-free。
 *
 * <p>{@link #cancel()} 绕过 enter/exit 直接调 {@code nativeCancel}，以便能作用在持有 lock 正在推理的调用。
 */
public final class MNNLlmSession implements AutoCloseable {

    private volatile long llmPtr;
    private final String modelDir;
    /** Host-provided delayed lane for a native call that ignored cancel; null only in JVM tests. */
    private final ScheduledExecutorService deferredReleaseScheduler;
    private volatile boolean released = false;
    private int activeCalls = 0;
    private final Object lock = new Object();
    /** P0: release 等 activeCalls 归零的硬超时——防 native hang 永久阻塞 lifecycle 线程。 */
    private static final long RELEASE_HARD_TIMEOUT_MS = 10_000L;

    private MNNLlmSession(long llmPtr, String modelDir,
            ScheduledExecutorService deferredReleaseScheduler) {
        this.llmPtr = llmPtr;
        this.modelDir = modelDir;
        this.deferredReleaseScheduler = deferredReleaseScheduler;
    }

    /**
     * 工厂：严格顺序 {@code createLlm → 6×setConfig → loadLlm}（配置必须在 load 前设置）。
     * 任一步失败 → release 已拿到的 ptr 并返回 null。
     */
    public static MNNLlmSession create(String modelDir, MnnLoadOptions options) {
        return create(modelDir, options, null);
    }

    /**
     * Production creation path. A timed-out native release is retried on the Host-owned bounded
     * scheduler, so this on-device adapter never creates a process worker by itself.
     * Passing {@code null} is retained solely for JVM tests and standalone consumers.
     */
    public static MNNLlmSession create(String modelDir, MnnLoadOptions options,
            ScheduledExecutorService deferredReleaseScheduler) {
        MnnLoadOptions opts = options != null ? options : MnnLoadOptions.cpuDefaults();
        opts.validate();
        File configFile = new File(modelDir, "llm_config.json");
        if (!configFile.exists()) return null;

        long ptr = MNNLlmNative.nativeCreateLlm(configFile.getAbsolutePath());
        if (ptr == 0) return null;
        try {
            String tmp = (opts.tmpPath != null && !opts.tmpPath.isEmpty()) ? opts.tmpPath : modelDir;
            setConfigOrThrow(ptr, "tmp_path", "\"" + escapeJson(tmp) + "\"");
            setConfigOrThrow(ptr, "async", "false");
            setConfigOrThrow(ptr, "precision", "\"" + opts.precision + "\"");
            setConfigOrThrow(ptr, "memory", "\"" + opts.memory + "\"");
            setConfigOrThrow(ptr, "backend_type", "\"" + opts.backendType + "\"");
            setConfigOrThrow(ptr, "thread_num", Integer.toString(opts.threadNum));
            // P2-15: enable_thinking 必须在 load 前设置——若 MNN 在 load 时快照 jinja 配置，
            // load 后再 setThinkingMode 可能无效（与 precision/memory 等同批 setConfig）。
            if (!MNNLlmNative.nativeSetConfig(ptr,
                    "{\"jinja\":{\"context\":{\"enable_thinking\":" + opts.enableThinking + "}}}")) {
                throw new IllegalStateException("nativeSetConfig failed: jinja.context.enable_thinking");
            }
            if (!MNNLlmNative.nativeLoadLlm(ptr)) {
                throw new IllegalStateException("nativeLoadLlm failed");
            }
            return new MNNLlmSession(ptr, modelDir, deferredReleaseScheduler);
        } catch (RuntimeException e) {
            MNNLlmNative.nativeReleaseLlm(ptr);
            return null;
        }
    }

    private static void setConfigOrThrow(long ptr, String key, String jsonValue) {
        String json = "{\"" + key + "\":" + jsonValue + "}";
        if (!MNNLlmNative.nativeSetConfig(ptr, json)) {
            throw new IllegalStateException("nativeSetConfig failed: " + key);
        }
    }

    /** 同步结构化生成；token 经 callback 回传。返回 native success。released/无 ptr 时返回 false。 */
    public boolean generateStructured(String messagesJson, String toolsJson, int maxTokens,
            MNNLlmNative.GenerationCallback callback) {
        long ptr = enter();
        if (ptr == 0) return false;
        try {
            return MNNLlmNative.nativeGenerateStreamStructured(ptr, messagesJson, toolsJson, maxTokens, callback);
        } finally {
            exit();
        }
    }

    public int countTokensStructured(String messagesJson, String toolsJson) {
        long ptr = enter();
        if (ptr == 0) return 0;
        try {
            return MNNLlmNative.nativeCountTokensWithStructuredMessages(ptr, messagesJson, toolsJson);
        } finally {
            exit();
        }
    }

    public void reset() {
        long ptr = enter();
        if (ptr == 0) return;
        try {
            MNNLlmNative.nativeReset(ptr);
        } finally {
            exit();
        }
    }

    /** 设置 thinking 模式（Qwen3 等：setConfig jinja.context.enable_thinking）。 */
    public void setThinkingMode(boolean enabled) {
        long ptr = enter();
        if (ptr == 0) return;
        try {
            MNNLlmNative.nativeSetConfig(ptr,
                    "{\"jinja\":{\"context\":{\"enable_thinking\":" + enabled + "}}}");
        } finally {
            exit();
        }
    }

    public MNNLlmContextInfo getContextInfo() {
        long ptr = enter();
        if (ptr == 0) return null;
        try {
            return MNNLlmContextInfo.fromJson(MNNLlmNative.nativeGetContextInfo(ptr));
        } finally {
            exit();
        }
    }

    /** 中断在跑的生成（不进 enter/exit，直接作用在 ptr）。供 OnDeviceModelGateway.abort 调用。 */
    public void cancel() {
        synchronized (lock) {
            if (released) return; // P2-13: 已 release 不再 nativeCancel（防 ptr 复用污染）
        }
        long ptr = llmPtr;
        if (ptr != 0) MNNLlmNative.nativeCancel(ptr);
    }

    /** 释放 native session（GB 级）。幂等。等在途 activeCalls 归零后才 nativeReleaseLlm。 */
    public void release() {
        long ptr;
        synchronized (lock) {
            if (released) return;
            released = true;
            ptr = llmPtr;
            llmPtr = 0;
        }
        if (ptr != 0) MNNLlmNative.nativeCancel(ptr); // 中断在跑的，让 in-flight native 尽快返回
        boolean interrupted = false;
        try {
            synchronized (lock) {
                long deadlineNanos = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(RELEASE_HARD_TIMEOUT_MS);
                while (activeCalls > 0) {
                    long leftNanos = deadlineNanos - System.nanoTime();
                    if (leftNanos <= 0) {
                        // P0-2: native cancel ignored the hard deadline. Production retries on
                        // the Host registry scheduler; only a standalone/JVM consumer uses the
                        // old daemon fallback, where there is no Host lifecycle to own it.
                        scheduleDeferredRelease(ptr);
                        return;
                    }
                    try {
                        long leftMillis = TimeUnit.NANOSECONDS.toMillis(leftNanos);
                        int leftNanosPart = (int) (leftNanos
                                - TimeUnit.MILLISECONDS.toNanos(leftMillis));
                        lock.wait(leftMillis, leftNanosPart);
                    } catch (InterruptedException e) {
                        // P1: 不立即 interrupt（避免后续 wait 立即抛导致 10s 忙等）——记录，完成后恢复
                        interrupted = true;
                    }
                }
            }
            if (ptr != 0) MNNLlmNative.nativeReleaseLlm(ptr);
        } finally {
            if (interrupted) Thread.currentThread().interrupt(); // 完成后恢复中断标志
        }
    }

    public String getModelDir() {
        return modelDir;
    }

    public boolean isReleased() {
        return released;
    }

    /**
     * Deterministic owner-facing release hook. Native memory must never depend on finalization:
     * {@link MnnOnDeviceLlm#close()} owns this call in the production gateway lifecycle.
     */
    @Override public void close() {
        release();
    }

    private void scheduleDeferredRelease(long stuckPtr) {
        if (deferredReleaseScheduler != null) {
            try {
                deferredReleaseScheduler.schedule(() -> retryDeferredRelease(stuckPtr),
                        2, TimeUnit.SECONDS);
                return;
            } catch (RejectedExecutionException ignored) {
                // Host is shutting down. Its process lifecycle owns the remaining native state.
                return;
            }
        }
        // A caller that does not provide a lifecycle-owned scheduler cannot safely start an
        // unmanaged retry thread. The native handle remains process-owned until that standalone
        // caller supplies an explicit scheduler or its process ends; production always injects
        // MatrixExecutorRegistry.modelRetirementScheduler().
    }

    private void retryDeferredRelease(long stuckPtr) {
        synchronized (lock) {
            if (activeCalls == 0) {
                MNNLlmNative.nativeReleaseLlm(stuckPtr);
                return;
            }
        }
        scheduleDeferredRelease(stuckPtr);
    }

    // —— 并发计数 ——
    private long enter() {
        synchronized (lock) {
            if (released || llmPtr == 0) return 0;
            if (activeCalls > 0) {
                // P1-3: MNN session 非线程安全，不允许并发 native 调用（gateway sessionLock 应保证串行）
                throw new IllegalStateException(
                        "MNNLlmSession 并发 native 调用（activeCalls=" + activeCalls + "）");
            }
            activeCalls++;
            return llmPtr;
        }
    }

    private void exit() {
        synchronized (lock) {
            if (activeCalls > 0) activeCalls--;
            if (activeCalls == 0) lock.notifyAll(); // 唤醒 release 等待
        }
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }
}
