package com.matrix.agent.debugtrace;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 唯一调试轨迹发射器（评估 v1.0 §4.3 契约 5）：每个事件先经
 * {@link DebugTraceRedactor}，然后<b>无条件</b> {@code Log.i("MatrixAgent", ...)}
 * 写入 logcat；仅当 UI 标志为 true 时，再复制到内存 ring buffer 并经订阅者下发。
 *
 * <p><b>双汇（dual sink）纪律</b>：日志侧永远开启（量产也输出脱敏事件——契约 7
 * "量产 UI 隐藏不等于停止日志"）；ring buffer 侧由构造注入的 {@code uiEnabled}
 * （BuildConfig.MATRIX_DEBUG_TRACE_UI）门控。单条日志按 {@link #MAX_LOG_BYTES}
 * 上限切分，附带 traceId/taskId/partIndex/partCount/阶段/时间——长 reasoning
 * 可在 logcat 重组且不因 Android 单条长度限制被截断。</p>
 *
 * <p>线程模型：emit 可从任意执行线程调用（同步写日志 + 短锁写 buffer）；
 * 订阅者回调在 emit 线程执行（Launcher 侧 Stub 自行 oneway 化）。</p>
 */
public final class DebugTraceEmitter {

    private static final String TAG = "MatrixAgent";

    /** 单条 logcat 上限（3 KiB——Android 单条 ~4KB 实限内留头部余量，契约 5）。 */
    static final int MAX_LOG_BYTES = 3 * 1024;

    /** ring buffer 容量（契约 2：有界内存；进程重启即清空）。 */
    private static final int RING_CAPACITY = 500;

    private final boolean uiEnabled;
    private final Deque<DebugTraceEvent> ring = new ArrayDeque<>();
    private final List<Consumer<DebugTraceEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong traceSequence = new AtomicLong();

    public DebugTraceEmitter(boolean uiEnabled) {
        this.uiEnabled = uiEnabled;
    }

    /** UI 汇是否开启（Host 侧 BuildConfig 门控；false 时仅日志汇）。 */
    public boolean uiEnabled() {
        return uiEnabled;
    }

    /**
     * 发射一条已净化事件的入口：payload 经 Redactor 后双汇。
     *
     * @param phase   DebugTraceEvent.PHASE_*
     * @param taskId  关联任务 id（可为 null）
     * @param payload 事件正文（**净化前**——本方法内部 redact）
     */
    public void emit(String phase, String taskId, String payload) {
        // redactBulk 不截断——长度由 3 KiB 分片管理（契约 5：长 reasoning 可重组）
        String safePayload = DebugTraceRedactor.redactBulk(payload);
        if (safePayload == null || safePayload.isEmpty()) {
            safePayload = "(redacted)"; // 禁词命中/空：保留事件骨架，丢弃内容
        }
        String safeTaskId = taskId == null ? "-" : DebugTraceRedactor.redactLine(taskId);
        if (safeTaskId == null) {
            safeTaskId = "-";
        }
        long timestampMs = System.currentTimeMillis();
        String traceId = "dt" + traceSequence.incrementAndGet();

        // 汇 1：无条件 logcat（3 KiB 分片 + 重组键）
        writeChunkedLog(phase, safeTaskId, traceId, timestampMs, safePayload);

        // 汇 2：UI 门控的 ring buffer + 订阅者
        if (uiEnabled) {
            List<DebugTraceEvent> chunks = chunkEvents(timestampMs, phase, safeTaskId,
                    traceId, safePayload);
            synchronized (ring) {
                for (DebugTraceEvent event : chunks) {
                    ring.addLast(event);
                    while (ring.size() > RING_CAPACITY) {
                        ring.removeFirst();
                    }
                }
            }
            for (DebugTraceEvent event : chunks) {
                for (Consumer<DebugTraceEvent> subscriber : subscribers) {
                    subscriber.accept(event);
                }
            }
        }
    }

    /** ring buffer 快照（调试页订阅后回放有界历史）。 */
    public List<DebugTraceEvent> snapshot() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    /** 订阅实时事件（Host Stub 的 IDebugTraceCallback 桥接注册于此）。 */
    public void subscribe(Consumer<DebugTraceEvent> subscriber) {
        if (uiEnabled && subscriber != null) {
            subscribers.add(subscriber);
        }
    }

    public void unsubscribe(Consumer<DebugTraceEvent> subscriber) {
        subscribers.remove(subscriber);
    }

    // ---------------------------------------------------------------- 内部

    private void writeChunkedLog(String phase, String taskId, String traceId,
            long timestampMs, String payload) {
        // 以 UTF-8 字节计量切分（logcat 限制按字节）
        byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalParts = Math.max(1, (bytes.length + MAX_LOG_BYTES - 1) / MAX_LOG_BYTES);
        int offset = 0;
        for (int part = 0; part < totalParts; part++) {
            int end = Math.min(bytes.length, offset + MAX_LOG_BYTES);
            String chunk = new String(bytes, offset, end - offset,
                    java.nio.charset.StandardCharsets.UTF_8);
            Log.i(TAG, "[DebugTrace] phase=" + phase + " task=" + taskId
                    + " trace=" + traceId + " part=" + part + "/" + totalParts
                    + " ts=" + timestampMs + " :: " + chunk);
            offset = end;
        }
    }

    private static List<DebugTraceEvent> chunkEvents(long timestampMs, String phase,
            String taskId, String traceId, String payload) {
        byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int totalParts = Math.max(1, (bytes.length + MAX_LOG_BYTES - 1) / MAX_LOG_BYTES);
        List<DebugTraceEvent> events = new ArrayList<>(totalParts);
        int offset = 0;
        for (int part = 0; part < totalParts; part++) {
            int end = Math.min(bytes.length, offset + MAX_LOG_BYTES);
            events.add(new DebugTraceEvent(timestampMs, phase, taskId, traceId, part,
                    totalParts, new String(bytes, offset, end - offset,
                            java.nio.charset.StandardCharsets.UTF_8)));
            offset = end;
        }
        return events;
    }
}
