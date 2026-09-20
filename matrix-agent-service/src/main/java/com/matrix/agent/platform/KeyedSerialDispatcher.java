package com.matrix.agent.platform;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * 共享有界线程池之上的 keyed 串行派发器：同一 key 的任务严格 FIFO 串行，
 * 不同 key 之间允许并发（受底层池容量约束）。
 *
 * <p>总待执行任务数有硬上限（{@code maxTotalPending}）；超限立即
 * {@link RejectedExecutionException}——对对话域而言，拒绝是可观察的 OVERLOADED 终态，
 * 优于无界排队烧掉请求预算（设计文档 §4.3/§7.1 的 M-1 裁决）。</p>
 *
 * <p>线程模型：{@link #execute} 可从任意线程调用（内部加锁）；每个“正在排水”的 key
 * 占用底层池一个线程，排水任务自身从队列取活直至该 key 排空。单个任务抛出异常不终止
 * 同 key 后续任务（异常吞掉并记录，语义与 caller 的 per-task 失败收敛一致）。</p>
 */
public final class KeyedSerialDispatcher {

    private static final String TAG = "MatrixAgent";

    private final String name;
    private final ExecutorService pool;
    private final int maxTotalPending;

    private final Object lock = new Object();
    private final Map<String, Deque<Runnable>> queues = new HashMap<>();
    private final Map<String, Boolean> draining = new HashMap<>();
    private int totalPending;
    private boolean shutdown;

    public KeyedSerialDispatcher(String name, ExecutorService pool, int maxTotalPending) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        this.name = name;
        this.pool = Objects.requireNonNull(pool, "pool");
        if (maxTotalPending <= 0) {
            throw new IllegalArgumentException("maxTotalPending 必须大于 0");
        }
        this.maxTotalPending = maxTotalPending;
    }

    /**
     * 追加一个 key 内 FIFO 的任务。
     *
     * @throws RejectedExecutionDispatcherException 总队列已满或派发器已关闭；任务未被入队，
     *         调用方必须立即给对应请求写出失败终态。
     */
    public void execute(String key, Runnable task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        synchronized (lock) {
            if (shutdown) {
                throw new RejectedExecutionDispatcherException(name + " 已关闭");
            }
            if (totalPending >= maxTotalPending) {
                throw new RejectedExecutionDispatcherException(
                        name + " 队列已满 pending=" + totalPending + "/" + maxTotalPending);
            }
            queues.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(task);
            totalPending++;
            if (draining.containsKey(key)) {
                return;
            }
            draining.put(key, Boolean.TRUE);
        }
        try {
            pool.execute(() -> drain(key));
        } catch (RejectedExecutionException poolSaturated) {
            // 底层池关闭/饱和。回滚“由我触发的排水登记”，但只撤销我刚入队的任务：
            // 在我释放锁到 pool.execute 之间，可能有并发调用者看到 draining 标记后入队并
            // 依赖我这次排水——它们的任务必须留在队列里，由这里的重试或该 key 的下一次
            // execute() 重新调度；绝不能整队丢弃造成静默丢失。
            boolean relaunchNeeded;
            synchronized (lock) {
                Deque<Runnable> queue = queues.get(key);
                if (queue != null && queue.peekLast() == task) {
                    queue.pollLast();
                    totalPending--;
                }
                if (queue == null || queue.isEmpty()) {
                    queues.remove(key);
                    draining.remove(key);
                    relaunchNeeded = false;
                } else {
                    draining.remove(key);
                    relaunchNeeded = true;
                }
            }
            if (relaunchNeeded) {
                try {
                    synchronized (lock) {
                        draining.put(key, Boolean.TRUE);
                    }
                    pool.execute(() -> drain(key));
                } catch (RejectedExecutionException stillSaturated) {
                    // 极端双重失败：登记已清除，任务滞留队列，等待该 key 下一次
                    // execute() 重新调度；进程死亡场景由上层恢复对账收敛（§5.3）。
                    synchronized (lock) {
                        draining.remove(key);
                    }
                    Log.e(TAG, "[" + name + "] 排水重启失败，key=" + key
                            + " 滞留任务等待下次调度");
                }
            }
            Log.w(TAG, "[" + name + "] 底层池拒绝排水 key=" + key);
            throw new RejectedExecutionDispatcherException(
                    name + " 底层执行池不可用: " + poolSaturated.getMessage());
        }
    }

    private void drain(String key) {
        while (true) {
            final Runnable task;
            synchronized (lock) {
                Deque<Runnable> queue = queues.get(key);
                task = queue == null ? null : queue.pollFirst();
                if (task == null) {
                    queues.remove(key);
                    draining.remove(key);
                    return;
                }
                totalPending--;
            }
            try {
                task.run();
            } catch (Throwable error) {
                // per-task 失败由任务自身（Coordinator 的终态收敛）负责；
                // 此处兜底记录，保证同 key 后续任务不被跳过。
                Log.w(TAG, "[" + name + "] key=" + key + " 任务异常 "
                        + error.getClass().getSimpleName(), error);
            }
        }
    }

    /** 快照语义：全部 key 的队列都已排空（不含正在执行中的最后一个任务）。 */
    public boolean isIdle() {
        synchronized (lock) {
            return totalPending == 0 && queues.isEmpty();
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return totalPending;
        }
    }

    public List<String> keysSnapshot() {
        synchronized (lock) {
            return new ArrayList<>(queues.keySet());
        }
    }

    /** 与 {@link java.util.concurrent.RejectedExecutionException} 同义，但独立类型便于测试断言。 */
    public static final class RejectedExecutionDispatcherException extends RuntimeException {
        public RejectedExecutionDispatcherException(String message) {
            super(message);
        }
    }
}
