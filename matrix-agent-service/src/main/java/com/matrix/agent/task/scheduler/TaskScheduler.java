package com.matrix.agent.task.scheduler;
import com.matrix.agent.task.*;

import com.matrix.agent.session.SessionManager;

import android.util.Log;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.session.SessionLockManager;

import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 主驾优先级调度器,叠加在 {@link SessionLockManager} 之上。
 *
 * <p>调度语义(用户决策):
 * <ul>
 *   <li>主驾查询/问答类({@code Actor.DRIVER + readOnlyHint=true})请求到达时,
 *       若 session 已被副驾查询占用,通过 {@code cancellationToken.cancel()} 抢占,
 *       副驾任务终态 {@link StopReason#CANCELLED} + {@link TaskState#PREEMPTED};</li>
 *   <li>车控写操作(readOnlyHint=false)不抢占——主驾查询排队等当前迭代结束,
 *       避免半路中断不可逆车控写;</li>
 *   <li>同 actor 多请求按到达顺序 FIFO(由 SessionLockManager 的 fair lock 保证);</li>
 *   <li>不同 session 互不影响(由 SessionLockManager 保证)。</li>
 * </ul>
 *
 * <p>实现策略:
 * <ul>
 *   <li>submit 时记录 per-session running task,主驾查询来时检查抢占条件;</li>
 *   <li>任务真正执行时通过 SessionLockManager.tryAcquire 串行化,保证 FIFO;</li>
 *   <li>不修改 SessionLockManager——它仍是底层 per-session 串行锁。</li>
 * </ul>
 */
public final class TaskScheduler {
    private static final String TAG = "MatrixAgent";

    private final ExecutorService workers;
    /** true 时 shutdown() 才真正关闭池;共享池由外部统一关闭。 */
    private final boolean ownsPool;
    private final SessionLockManager sessionLockManager;
    /**
     * per-session FIFO 队列——同 session 内所有 submit 都被记录,
     * 让 {@link #tryPreemptPassengerReadOnly} 能看到真正阻塞 session 的 task。
     * <p>旧实现用 putIfAbsent 语义,新 task 在旧 task 未完成时不进 map,
     * 导致主驾抢占判定只看到第一个被记录的 task,排队中的请求无法被抢占。
     */
    private final ConcurrentMap<String, Deque<RunningTask>> runningTasks = new ConcurrentHashMap<>();

    /** Test-only convenience constructor; Host runtime injects its bounded scheduler lane. */
    public TaskScheduler(int parallelism, SessionLockManager sessionLockManager) {
        this(parallelism, sessionLockManager, null, "test-owned");
    }

    /** Uses a Host-owned executor without coupling the scheduler to a concrete pool type. */
    public TaskScheduler(int parallelism, SessionLockManager sessionLockManager,
            ExecutorService sharedExecutor) {
        this(parallelism, sessionLockManager, sharedExecutor, "HostExecutor");
    }

    private TaskScheduler(int parallelism, SessionLockManager sessionLockManager,
            ExecutorService sharedExecutor, String sharedExecutorName) {
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism 必须大于 0");
        if (sessionLockManager == null) throw new IllegalArgumentException("sessionLockManager 不能为空");
        if (sharedExecutor != null) {
            this.workers = sharedExecutor;
            this.sessionLockManager = sessionLockManager;
            this.ownsPool = false;
            Log.i(TAG, "[Scheduler] init parallelism=" + parallelism
                    + " sharedPool=" + sharedExecutorName);
        } else {
            this.workers = Executors.newFixedThreadPool(parallelism, r -> {
                Thread t = new Thread(r, "matrix-scheduler-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            });
            this.sessionLockManager = sessionLockManager;
            this.ownsPool = true;
            Log.i(TAG, "[Scheduler] init parallelism=" + parallelism);
        }
    }

    /**
     * 提交请求,异步执行。返回 Future 给调用方同步等待结果。
     *
     * <p>主驾查询请求会触发对当前 session 内副驾只读任务的抢占;
     * 其他请求(车控写 / 副驾 / 主驾写)按 FIFO 排队 SessionLockManager。
     */
    public Future<AgentOutcome> submit(final AgentRequest request, final TaskRunner runner) {
        if (request == null) throw new IllegalArgumentException("request 不能为空");
        if (runner == null) throw new IllegalArgumentException("runner 不能为空");

        // Scheduler 用 arbitrationKey(同车仲裁),不再用 sessionId。
        // 这样主驾和副驾可以共享调度队列参与抢占,但 SessionManager / SteerMailbox 仍用
        // sessionId 按乘员隔离对话上下文与运行时干预通道。
        final String arbitrationKey = request.getArbitrationKey();
        // 主驾查询类请求:在 submit 时尝试抢占 arbitration 队列中正在跑的副驾只读任务
        if (request.getActor() == Actor.DRIVER && request.isReadOnlyHint()) {
            tryPreemptPassengerReadOnly(arbitrationKey);
        }

        final RunningTask newTask = new RunningTask(request);
        // 所有 submit 都记录到 FIFO 队列尾,旧实现的 putIfAbsent 语义
        // 会让排队中的 task 无法被记录,主驾抢占判定失效。
        runningTasks.computeIfAbsent(arbitrationKey, k -> new ConcurrentLinkedDeque<>())
                .addLast(newTask);

        Callable<AgentOutcome> task = () -> {
            long started = System.nanoTime();
            long lockWaitDeadline = System.currentTimeMillis()
                    + Math.max(1_000L, request.remainingMillis());
            SessionLockManager.Handle handle = null;
            try {
                // 通过 SessionLockManager 串行化同 arbitrationKey 的任务——FIFO 由 fair lock 保证
                while (handle == null && System.currentTimeMillis() < lockWaitDeadline) {
                    try {
                        handle = sessionLockManager.tryAcquire(arbitrationKey, request.remainingMillis());
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return terminalOutcome(request, TaskState.CANCELLED, StopReason.CANCELLED,
                                "scheduler wait interrupted");
                    }
                }
                if (handle == null) {
                    return terminalOutcome(request, TaskState.TIMED_OUT, StopReason.TIMEOUT,
                            "scheduler arbitration lock acquire timeout");
                }
                Log.i(TAG, "[Scheduler] run req=" + request.getRequestId()
                        + " arbitration=" + arbitrationKey
                        + " session=" + request.getSessionId()
                        + " actor=" + request.getActor()
                        + " readOnly=" + request.isReadOnlyHint()
                        + " lockWaitMs=" + ((System.nanoTime() - started) / 1_000_000L));
                AgentOutcome outcome = runner.run(request);
                // Scheduler 自己 cancel 的 task → 重映射 CANCELLED 为 PREEMPTED
                outcome = remapIfPreempted(newTask, outcome);
                long durationMs = (System.nanoTime() - started) / 1_000_000L;
                Log.i(TAG, "[Scheduler] done req=" + request.getRequestId()
                        + " finalState=" + outcome.getFinalState()
                        + " stopReason=" + outcome.getStopReason()
                        + " durationMs=" + durationMs);
                return outcome;
            } finally {
                if (handle != null) {
                    handle.close();
                }
                runningTasks.computeIfPresent(arbitrationKey, (sid, deque) -> {
                    deque.remove(newTask);  // 精确移除自己的 task
                    return deque.isEmpty() ? null : deque;
                });
            }
        };

        Future<AgentOutcome> future;
        try {
            future = workers.submit(task);
        } catch (RejectedExecutionException rejected) {
            // schedulerPool 队列满 / Executor 关闭——返回已完成 Future,
            // 值是 REJECTED terminalOutcome(与 EXECUTION_UNKNOWN 同模式)。
            // Repository 兜底 audit 自动覆盖(终态审计)。
            Log.w(TAG, "[Scheduler] submit REJECTED req=" + request.getRequestId()
                    + " (schedulerPool 队列满 / executor 关闭)");
            AgentOutcome rejectedOutcome = terminalOutcome(request, TaskState.REJECTED,
                    StopReason.REJECTED, "调度器队列满,任务被拒绝");
            Future<AgentOutcome> rejectedFuture;
            try {
                rejectedFuture = java.util.concurrent.CompletableFuture.completedFuture(rejectedOutcome);
            } catch (Throwable linkError) {
                // CompletableFuture 在某些早期 Android API 不存在——回退到 ListenableFuture API
                throw new RejectedExecutionException("scheduler rejected and no fallback future", linkError);
            }
            newTask.bind(rejectedFuture);
            return rejectedFuture;
        }
        newTask.bind(future);
        return future;
    }

    /**
     * 主驾请求抢占副驾只读任务。
     *
     * <p>从 FIFO 队首找第一个未完成 task——同 arbitration 队列内可能已有多个
     * 排队,旧实现只看第一个被记录的,可能错失真正阻塞队列的 task。
     *
     * <p>抢占前给 RunningTask 打 preempted 标记,runner 返回 CANCELLED 后
     * 由 {@link #remapIfPreempted} 重映射为 {@link StopReason#PREEMPTED} +
     * {@link TaskState#PREEMPTED},避免调用方在外层根据"被谁取消"猜测映射。
     *
     * <p>参数从 sessionId 改为 arbitrationKey,与 SessionManager/
     * SteerMailbox 用的 conversationSessionId 解耦。
     */
    private void tryPreemptPassengerReadOnly(String arbitrationKey) {
        Deque<RunningTask> deque = runningTasks.get(arbitrationKey);
        if (deque == null) return;
        for (RunningTask current : deque) {
            if (current.isDone()) continue;
            AgentRequest running = current.request;
            if (running.getActor() != Actor.PASSENGER) return;
            if (!running.isReadOnlyHint()) return;
            Log.i(TAG, "[Scheduler] preempt arbitration=" + arbitrationKey
                    + " passengerReq=" + running.getRequestId()
                    + " (read-only) by driver");
            current.markPreempted();
            running.getCancellationToken().cancel();
            return;
        }
    }

    /**
     * Scheduler 自己 cancel 导致的 CANCELLED 重映射为 PREEMPTED。
     *
     * <p>语义:Scheduler.tryPreemptPassengerReadOnly 调 token.cancel() 之前已给 RunningTask
     * 打 preempted 标记。任务 runner 返回 CANCELLED outcome 时,这里把 stopReason/finalState
     * 改写为 PREEMPTED,与 {@link TaskState#PREEMPTED} 一致。
     *
     * <p>runner 返回非 CANCELLED 时不重映射(防御性,避免错误覆盖 SUCCEEDED 等)。
     *
     * <p>重映射时必须同步把 trajectory.stopReason 从 CANCELLED 改写为
     * PREEMPTED——否则 Repository 兜底 audit 落库后会出现"结构化列 PREEMPTED /
     * trajectoryJson.trajectory.stopReason CANCELLED"的语义矛盾。trajectory 由 Engine 在
     * token.cancel 后用 CANCELLED finish,本方法调 {@link Trajectory#rewriteStopReason}
     * 受控覆盖(仅允许已 finish 状态调用),保留 iterations / duration / toolCalls 不变。
     */
    private static AgentOutcome remapIfPreempted(RunningTask task, AgentOutcome outcome) {
        if (!task.preempted) return outcome;
        if (outcome.getStopReason() != StopReason.CANCELLED) return outcome;
        outcome.getTrajectory().rewriteStopReason(StopReason.PREEMPTED);
        return new AgentOutcome(
                outcome.getRequestId(),
                TaskState.PREEMPTED,
                StopReason.PREEMPTED,
                outcome.getTrajectory(),
                outcome.getDurationMillis());
    }

    /** 测试 / 监控用:返回所有 session 的队列内 task 总数(含 running 和 queued)。 */
    public int runningCount() {
        int total = 0;
        for (Deque<RunningTask> deque : runningTasks.values()) {
            total += deque.size();
        }
        return total;
    }

    /** 测试用:关闭线程池。共享池不在此处关闭(由外部统一管理)。 */
    public void shutdown() {
        if (ownsPool) {
            workers.shutdown();
        }
    }

    /** 单任务执行钩子——把 AgentEngine.execute 包装成可注入的 lambda。 */
    public interface TaskRunner {
        AgentOutcome run(AgentRequest request);
    }

    private static AgentOutcome terminalOutcome(AgentRequest request, TaskState state,
            StopReason reason, String message) {
        Trajectory trajectory = new Trajectory();
        trajectory.finish(reason, 0L, 0);
        return new AgentOutcome(request.getRequestId(), state, reason, trajectory, 0L);
    }

    private static final class RunningTask {
        final AgentRequest request;
        volatile Future<AgentOutcome> future;
        /** Scheduler 自己 cancel 的 task 标记,用于 outcome 重映射 PREEMPTED。 */
        volatile boolean preempted;

        RunningTask(AgentRequest request) {
            this.request = request;
        }

        void bind(Future<AgentOutcome> future) {
            this.future = future;
        }

        void markPreempted() {
            preempted = true;
        }

        boolean isDone() {
            return future != null && future.isDone();
        }
    }
}
