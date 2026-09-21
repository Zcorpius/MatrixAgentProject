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
    /** null 表示仅内存调试（JVM 测试）或量产日志模式。 */
    private final DebugTraceStore store;
    private final Deque<DebugTraceEvent> ring = new ArrayDeque<>();
    private final List<Consumer<DebugTraceEvent>> subscribers = new CopyOnWriteArrayList<>();
    private final AtomicLong traceSequence = new AtomicLong();
    /** 防止进程重启后 dt1:0 与 SQLCipher 中旧分片的主键碰撞。 */
    private final String processTraceNonce = java.util.UUID.randomUUID().toString()
            .replace("-", "").substring(0, 12);

    public DebugTraceEmitter(boolean uiEnabled) {
        this(uiEnabled, null);
    }

    /**
     * @param store 仅 debug/internal 装配的 SQLCipher 投影端口；null 保持纯内存测试语义
     */
    public DebugTraceEmitter(boolean uiEnabled, DebugTraceStore store) {
        this.uiEnabled = uiEnabled;
        this.store = store;
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
        long eventSequence = traceSequence.incrementAndGet();
        String traceId = "dt-" + processTraceNonce + "-" + eventSequence;

        List<DebugTraceEvent> chunks = chunkEvents(timestampMs, phase, safeTaskId, traceId,
                eventSequence, safePayload);

        // 汇 1：无条件 logcat（3 KiB 分片 + 重组键）
        writeChunkedLog(chunks);

        // 汇 2：UI 门控的 ring buffer + 订阅者
        if (uiEnabled) {
            // 有持久化 Store 时，先以 runtimeRequestId 解析到用户消息并完成 SQLCipher
            // 写入，再发布实时事件。无映射的模型配置/标题任务只会留在日志里。
            if (store != null) store.persist(chunks, this::publishUi);
            else for (DebugTraceEvent event : chunks) publishUi(event);
        }
    }

    /** ring buffer 快照（调试页订阅后回放有界历史）。 */
    public List<DebugTraceEvent> snapshot() {
        synchronized (ring) {
            return new ArrayList<>(ring);
        }
    }

    /** 调试构建的 SQLCipher 历史；量产/纯内存模式刻意返回空。 */
    public List<DebugTraceEvent> history(String hostUserMessageId, int limit) {
        if (!uiEnabled || store == null) return java.util.Collections.emptyList();
        return store.history(hostUserMessageId, limit);
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

    private void publishUi(DebugTraceEvent event) {
        synchronized (ring) {
            ring.addLast(event);
            while (ring.size() > RING_CAPACITY) ring.removeFirst();
        }
        for (Consumer<DebugTraceEvent> subscriber : subscribers) subscriber.accept(event);
    }

    private static void writeChunkedLog(List<DebugTraceEvent> chunks) {
        for (DebugTraceEvent event : chunks) {
            Log.i(TAG, "[DebugTrace] phase=" + event.phase + " task=" + event.taskId
                    + " trace=" + event.traceId + " part=" + event.partIndex + "/"
                    + event.partCount + " ts=" + event.timestampMs + " :: "
                    + event.payload);
        }
    }

    private static List<DebugTraceEvent> chunkEvents(long timestampMs, String phase,
            String taskId, String traceId, long eventSequence, String payload) {
        List<String> chunks = splitUtf8Safely(payload);
        List<DebugTraceEvent> events = new ArrayList<>(chunks.size());
        for (int part = 0; part < chunks.size(); part++) {
            events.add(new DebugTraceEvent(timestampMs, phase, taskId, traceId,
                    eventSequence, part, chunks.size(), chunks.get(part), null, null, null));
        }
        return events;
    }

    /** 按 code point 切分，避免原实现按任意 UTF-8 字节截断而损坏中文/emoji。 */
    private static List<String> splitUtf8Safely(String payload) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentBytes = 0;
        for (int offset = 0; offset < payload.length();) {
            int codePoint = payload.codePointAt(offset);
            String glyph = new String(Character.toChars(codePoint));
            int glyphBytes = glyph.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (currentBytes > 0 && currentBytes + glyphBytes > MAX_LOG_BYTES) {
                chunks.add(current.toString());
                current.setLength(0);
                currentBytes = 0;
            }
            current.append(glyph);
            currentBytes += glyphBytes;
            offset += Character.charCount(codePoint);
        }
        if (current.length() > 0 || chunks.isEmpty()) chunks.add(current.toString());
        return chunks;
    }
}
