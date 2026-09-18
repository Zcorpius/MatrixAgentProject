package com.matrix.agent.task.identity;

import android.util.Log;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 取消令牌 + abort hook 监听。
 *
 * <p>cancel() 时同步调用所有已注册的 abort hook,让上层(ModelCallExecutor)
 * 能在 cancel 触发的瞬间执行传输层 abort（如 OkHttp {@code Call.cancel()}），
 * 而不依赖 50ms polling 检查周期。
 *
 * <p>语义保证:
 * <ul>
 *   <li>{@link #cancel()} 幂等——多次调用只触发一次 abort hook 通知;</li>
 *   <li>{@link #registerAbortHook(Runnable)} race-safe——若 cancel 已发生,新注册的 hook 立即同步执行;</li>
 *   <li>abort hook 异常被吞掉(只打 log),保证一个 hook 失败不影响其他 hook 或 cancel 流程;</li>
 *   <li>{@link #removeAbortHook(Runnable)} O(n),仅用于清理(call 结束后移除已挂的 hook 防止内存泄漏)。</li>
 * </ul>
 */
public final class CancellationToken {
    private static final String TAG = "MatrixAgent";

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> abortHooks = new CopyOnWriteArrayList<>();

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            Log.d(TAG, "[Cancel] cancel() called again, already cancelled (idempotent)");
            return;
        }
        Log.d(TAG, "[Cancel] cancel() invoked, firing abort hooks count=" + abortHooks.size());
        for (Runnable hook : abortHooks) {
            try {
                hook.run();
            } catch (Throwable error) {
                Log.w(TAG, "[Cancel] abort hook threw " + error.getClass().getSimpleName()
                        + ": " + (error.getMessage() == null ? "" : error.getMessage()));
            }
        }
    }

    /**
     * 注册 abort hook——cancel() 时同步调用。
     *
     * <p>Race-safe:若 cancel() 已经触发,本方法注册的 hook 立即同步执行(在调用方线程)。
     * 这避免"cancel 已发生 → 新 hook 注册 → hook 永不执行"的丢失窗口。
     */
    public void registerAbortHook(Runnable hook) {
        if (hook == null) return;
        abortHooks.add(hook);
        if (cancelled.get()) {
            Log.d(TAG, "[Cancel] registerAbortHook after cancel() — invoking immediately");
            try {
                hook.run();
            } catch (Throwable error) {
                Log.w(TAG, "[Cancel] late abort hook threw " + error.getClass().getSimpleName());
            }
        }
    }

    /** 移除已注册的 abort hook(用于 call 完成后清理,防止内存泄漏)。 */
    public void removeAbortHook(Runnable hook) {
        if (hook == null) return;
        abortHooks.remove(hook);
    }

    /** 测试 / 监控用:当前已注册的 abort hook 数量。 */
    public int abortHookCount() {
        return abortHooks.size();
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }
}
