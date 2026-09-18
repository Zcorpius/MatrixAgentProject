package com.matrix.agent.contract;
import com.matrix.agent.platform.MatrixExecutorRegistry;

import android.util.Log;

import com.matrix.agent.contract.RetirableModelGateway;

import java.util.concurrent.ExecutorService;

/**
 * 端侧 Gateway 生命周期管理（评审 P0-2）：模型切换时异步退役旧 {@link RetirableModelGateway}，
 * 等在途 native 调用返回（lease 归零）后才 {@code close()}，避免泄漏 GB 级 MNN session。
 *
 * <p><b>绝不强 destroy</b>：超时未 drained 只标记 stuck（继续拒新请求），<b>不</b>调用 close——
 * native 未返回时 destroy 会 use-after-free。由 Host 的受限 lifecycle executor 注入。
 */
public final class GatewayLifecycleManager {

    private static final String TAG = "MatrixAgent";
    /** 等待在途 native 返回的上限；超时只标 stuck 不 destroy。 */
    private static final long STUCK_TIMEOUT_MS = 30_000L;

    private final ExecutorService lifecycleExecutor;
    /**
     * drain 任务专用单线程 executor——避免 retireAsync 的 awaitDrained(30s) 长时间占用
     * 共享 ioPool（会阻塞 ModelCallExecutor / ToolExecutor 等 I/O 任务排队）。daemon 线程，进程退出时回收。
     */
    public GatewayLifecycleManager(ExecutorService lifecycleExecutor) {
        this.lifecycleExecutor = lifecycleExecutor;
    }

    /**
     * 异步退役：retire → awaitDrained → close（drained 时）/ 标 stuck（超时，不 destroy）。
     * 非阻塞——立即返回，退役在 ioPool 后台进行。
     */
    public void retireAsync(final RetirableModelGateway gateway) {
        if (gateway == null) return;
        // P0-6: retire 同步标记（立即拒新请求），不依赖 ioPool 排队
        gateway.retire();
        if (lifecycleExecutor == null) {
            safeClose(gateway); // 无 ioPool，同步 close 兜底
            return;
        }
        try {
            // drain 跑在 Host 专用 lifecycle executor，不占用请求/模型 I/O worker。
            lifecycleExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean drained = gateway.awaitDrained(STUCK_TIMEOUT_MS);
                        if (drained) {
                            gateway.close();
                            Log.i(TAG, "[Lifecycle] on-device gateway drained + closed");
                        } else {
                            Log.w(TAG, "[Lifecycle] on-device gateway STUCK (not drained in "
                                    + STUCK_TIMEOUT_MS + "ms) — retired, NOT destroyed");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "[Lifecycle] awaitDrained interrupted — gateway retired, NOT destroyed");
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // lifecycle executor 关闭/队列满——不在调用方线程阻塞（ANR 风险）。
            // retire 已同步完成（gateway.retire() 在 L34 已执行，新请求被拒），
            // native 资源等进程退出回收——维持 retired 状态比阻塞 main thread 安全。
            Log.w(TAG, "[Lifecycle] lifecycle executor rejected retire task — gateway retired, native defers to process exit");
        }
    }

    /** Executor is Host-owned; lifecycle shutdown is performed by MatrixExecutorRegistry. */
    public void shutdown() {
        // no-op
    }

    /** 同步 close 兜底（短超时 awaitDrained，不长时间阻塞调用方）。 */
    private void safeClose(RetirableModelGateway gateway) {
        try {
            if (gateway.awaitDrained(5000)) {
                gateway.close();
                Log.i(TAG, "[Lifecycle] on-device gateway sync-closed (fallback)");
            } else {
                Log.w(TAG, "[Lifecycle] sync close STUCK (not drained in 5s) — retired, NOT destroyed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
