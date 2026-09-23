package com.matrix.agent.launcher.data;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import android.util.Log;

/**
 * 草稿命令的会话串行 lane（输入交互增强 I4 §7.2 双层防线的 Launcher 侧半边）。
 *
 * <p>同一会话的 saveDraft / submitTextOrAppend / discardDraft 经由同一个 FIFO
 * 串行器执行；不同会话可复用后台线程并行，避免一个慢 Binder 调用把所有会话的草稿
 * 都冻结。保证“debounce 保存 → 提交 → 迟到保存”不会乱序到达 Host——即使有人未来绕过
 * 本 lane，Host 侧 tombstone 仍会兜底拒绝旧 instance。回调统一
 * 经 {@link LauncherHostGateway#dispatchToMain} 回主线程，ViewModel 只有一个状态写入者。</p>
 */
public final class DraftCommandLane {

    private static final String TAG = "MatrixAgent";

    private final LauncherHostGateway gateway;
    private final Executor executor;
    private final ConcurrentHashMap<String, SerialQueue> queues = new ConcurrentHashMap<>();

    public DraftCommandLane(LauncherHostGateway gateway, Executor executor) {
        this.gateway = gateway;
        this.executor = executor;
    }

    /** 每 conversation 独立 FIFO；提交返回前不允许下一条同会话草稿命令越过它。 */
    public void execute(String conversationId, Runnable command) {
        if (conversationId == null || command == null) return;
        queues.computeIfAbsent(conversationId, SerialQueue::new).enqueue(command);
    }

    /** Host 结果统一回主线程。 */
    public <T> void deliver(LauncherHostGateway.Result<T> result,
            java.util.function.Consumer<LauncherHostGateway.Result<T>> receiver) {
        gateway.dispatchToMain(() -> receiver.accept(result));
    }

    private final class SerialQueue {
        private final String conversationId;
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private boolean draining;

        SerialQueue(String conversationId) {
            this.conversationId = conversationId;
        }

        void enqueue(Runnable command) {
            boolean schedule;
            synchronized (this) {
                pending.addLast(command);
                schedule = !draining;
                if (schedule) draining = true;
            }
            if (schedule) scheduleDrain();
        }

        private void scheduleDrain() {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException unavailable) {
                synchronized (this) {
                    draining = false;
                }
                // This is only expected during Application shutdown. Commands are intentionally
                // not rerun on a different executor: doing so could violate the per-key order.
                logWarning("[DraftLane] backend unavailable conv=" + conversationId,
                        unavailable);
            }
        }

        private void drain() {
            while (true) {
                Runnable command;
                synchronized (this) {
                    command = pending.pollFirst();
                    if (command == null) {
                        draining = false;
                        // Keep the idle per-conversation serializer. Removing it here races a
                        // concurrent computeIfAbsent/enqueue that already holds this instance,
                        // which could split one conversation across two queues and break FIFO.
                        // Conversation count is deliberately bounded by the Host list contract.
                        return;
                    }
                }
                try {
                    command.run();
                } catch (RuntimeException failure) {
                    logWarning("[DraftLane] command failed conv=" + conversationId, failure);
                }
            }
        }
    }

    /** android.util.Log is intentionally non-fatal even in plain JVM tests. */
    private static void logWarning(String message, Throwable error) {
        try {
            Log.w(TAG, message, error);
        } catch (RuntimeException ignored) {
            // Unit-test Android stubs may throw; logging must never break the serial lane.
        }
    }
}
