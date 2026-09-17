package com.matrix.agent.task;

import android.util.Log;

import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.CancellationToken;

import com.matrix.agent.model.ModelApiException;

import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 用请求 deadline + cancel 令牌包裹 ModelGateway 调用。
 *
 * <p>取消 50ms polling,改成 {@code future.get(budget, MILLISECONDS)} 阻塞等。
 * cancel 触发时,通过 {@link CancellationToken#registerAbortHook(Runnable)} 同步触发
 * {@code future.cancel(true)} + {@link CancellableModelCall#abort()},让传输层 abort
 * 与 cancel 令牌在同一时间点生效——延迟从 50ms 降到接近 0。
 *
 * <p>abort 链:
 * <ol>
 *   <li>外部线程调 {@code token.cancel()};</li>
 *   <li>CancellationToken 同步触发所有 abort hook;</li>
 *   <li>本类注册的 abort hook 执行 {@code future.cancel(true)}(让 worker thread 收到 interrupt)
 *       + {@code call.abort()}(由 gateway 实现传输层 abort,后续版本接 OkHttp 时挂 Call.cancel);</li>
 *   <li>{@code future.get(...)} 抛 CancellationException → 本方法返回 {@code Result.terminal(CANCELLED)}。</li>
 * </ol>
 *
 * <p>deadline 路径:{@code future.get(budget, MILLISECONDS)} 抛 TimeoutException →
 * 本方法主动调 {@code token.cancel()} 触发上述 abort 链 → 返回 {@code Result.terminal(TIMEOUT)}。
 */
public final class ModelCallExecutor {
    private static final String TAG = "MatrixAgent";
    /** Shared bounded network lane for remote model calls. */
    private final ExecutorService networkWorkers;
    /** Dedicated, normally single-worker lane for native local generation. */
    private final ExecutorService localWorkers;

    /**
     * Test-only convenience constructor. Production composition always supplies the Host-owned
     * bounded lanes through {@link #ModelCallExecutor(int, ExecutorService, ExecutorService)}.
     */
    public ModelCallExecutor(int parallelism) {
        this(parallelism, (ExecutorService) null, "test-owned", null);
    }

    /** Uses the Host registry's I/O executor while retaining the legacy constructor for tests. */
    public ModelCallExecutor(int parallelism, ExecutorService sharedExecutor) {
        this(parallelism, sharedExecutor, "HostExecutor");
    }

    /**
     * Host composition constructor. Native MNN calls are serialised onto {@code localExecutor},
     * while all other gateways retain the shared network executor semantics.
     */
    public ModelCallExecutor(int parallelism, ExecutorService networkExecutor,
            ExecutorService localExecutor) {
        this(parallelism, networkExecutor, "HostNetworkExecutor", localExecutor);
    }

    private ModelCallExecutor(int parallelism, ExecutorService sharedExecutor,
            String sharedExecutorName) {
        this(parallelism, sharedExecutor, sharedExecutorName, null);
    }

    private ModelCallExecutor(int parallelism, ExecutorService sharedExecutor,
            String sharedExecutorName, ExecutorService localExecutor) {
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism 必须大于 0");
        if (sharedExecutor != null) {
            this.networkWorkers = sharedExecutor;
            this.localWorkers = localExecutor == null ? sharedExecutor : localExecutor;
            Log.i(TAG, "[ModelCall] init parallelism=" + parallelism
                    + " sharedPool=" + sharedExecutorName
                    + " localLane=" + (localExecutor == null ? "shared" : "dedicated"));
        } else {
            this.networkWorkers = Executors.newFixedThreadPool(parallelism, new ModelThreadFactory());
            this.localWorkers = networkWorkers;
            Log.i(TAG, "[ModelCall] init parallelism=" + parallelism);
        }
    }

    public Result decide(ModelGateway gateway, ModelTurnRequest request) {
        AgentRequest agentRequest = request.getAgentRequest();
        long budgetMillis = agentRequest.remainingMillis();
        if (budgetMillis <= 0) {
            Log.w(TAG, "[ModelCall] pre-call deadline already passed, terminal=TIMEOUT");
            return Result.terminal(StopReason.TIMEOUT, "调用模型前已超过截止时间");
        }

        long callStarted = System.nanoTime();
        Log.d(TAG, "[ModelCall] submit gateway=" + gateway.getClass().getSimpleName()
                + " budgetMs=" + budgetMillis
                + " req=" + agentRequest.getRequestId()
                + " mode=prepare+abort-hook");

        CancellableModelCall call = gateway.prepare(request);
        CancellationToken token = agentRequest.getCancellationToken();

        Future<ModelTurn> future;
        try {
            ExecutorService worker = gateway.executionLane() == ModelGateway.ExecutionLane.SERIAL_LOCAL
                    ? localWorkers : networkWorkers;
            future = worker.submit(new Callable<ModelTurn>() {
                @Override
                public ModelTurn call() {
                    return call.call();
                }
            });
        } catch (RejectedExecutionException rejected) {
            // ioPool 队列满 / Executor 关闭——直接 POLICY_HALT,
            // AgentEngine 收到 POLICY_HALT 后立刻退出 Loop,不重试。
            Log.w(TAG, "[ModelCall] submit REJECTED req=" + agentRequest.getRequestId()
                    + " (ioPool 队列满 / executor 关闭)");
            return Result.terminal(StopReason.POLICY_HALT, "模型调用被拒绝(ioPool 队列满)");
        }

        // abort hook:cancel 触发时同步执行 future.cancel(true) + call.abort()
        // future.cancel(true) 让 worker thread 收到 interrupt;call.abort() 由 gateway 实现(后续版本真 abort)
        Runnable abortHook = () -> {
            Log.d(TAG, "[ModelCall] abort hook fired, calling future.cancel(true) + call.abort()");
            future.cancel(true);
            try {
                call.abort();
            } catch (Throwable error) {
                Log.w(TAG, "[ModelCall] call.abort() threw " + error.getClass().getSimpleName()
                        + ": " + (error.getMessage() == null ? "" : error.getMessage()));
            }
        };
        if (token != null) {
            token.registerAbortHook(abortHook);
        }

        try {
            ModelTurn turn = future.get(budgetMillis, TimeUnit.MILLISECONDS);
            Log.d(TAG, "[ModelCall] gateway returned turn hasToolCalls=" + turn.hasToolCalls()
                    + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0)
                    + " costMs=" + elapsedMillis(callStarted));
            return Result.success(turn);
        } catch (TimeoutException timeout) {
            // deadline 到——主动触发 abort 链(等同于外部 cancel)
            Log.w(TAG, "[ModelCall] deadline reached, terminal=TIMEOUT costMs="
                    + elapsedMillis(callStarted));
            if (token != null) {
                token.cancel();
            } else {
                abortHook.run();
            }
            return Result.terminal(StopReason.TIMEOUT, "模型调用超过请求截止时间");
        } catch (CancellationException cancelled) {
            // abort hook 触发了 future.cancel(true) → 这里说明外部 token.cancel() 已发生
            Log.w(TAG, "[ModelCall] future cancelled by abort hook, terminal=CANCELLED costMs="
                    + elapsedMillis(callStarted));
            return Result.terminal(StopReason.CANCELLED, "模型调用已取消");
        } catch (InterruptedException interrupted) {
            Log.w(TAG, "[ModelCall] executor thread interrupted, terminal=CANCELLED");
            Thread.currentThread().interrupt();
            return Result.terminal(StopReason.CANCELLED, "模型调用线程已中断");
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            // gateway 抛 CancellationException（端侧 cancel/retire 在途）→ CANCELLED terminal，
            // 不走 POLICY_HALT（取消不是协议错误）
            if (cause instanceof CancellationException) {
                Log.w(TAG, "[ModelCall] gateway cancelled (CancellationException), terminal=CANCELLED costMs="
                        + elapsedMillis(callStarted));
                return Result.terminal(StopReason.CANCELLED, "模型调用已取消(端侧)");
            }
            // 网络层 / 超时异常映射为 TIMEOUT 终态(而非 POLICY_HALT):本层是模型决策阶段(调 LLM
            // 决定下一步,尚未进入工具执行),网络/超时属时间类临时故障,归 TIMEOUT 语义最准确;
            // POLICY_HALT 留给协议/模型不可恢复错误(RateLimit/Server 重试耗尽、4xx、JSON 解析错)。
            // (注:写操作 EXECUTION_UNKNOWN 是 AgentRuntimeRepository 在整体 future 超时、且工具
            // 可能已下发时收敛的,与本层模型决策超时无关。)
            // 沿 cause 链查找——ModelApiException 从 post() 抛出后可能被中间层(LlmModelGateway
            // 的 catch(Exception)→IllegalStateException、或未来 CancellableModelCall)包装,顶层
            // instanceof 会漏判被包装的情况。findNetworkOrTimeout 对所有包装层级都健壮。
            ModelApiException networkOrTimeout = findNetworkOrTimeout(cause);
            if (networkOrTimeout != null) {
                Log.w(TAG, "[ModelCall] gateway network/timeout " + networkOrTimeout.getClass().getSimpleName()
                        + " (unwrapped from " + cause.getClass().getSimpleName() + ")"
                        + " -> terminal=TIMEOUT costMs=" + elapsedMillis(callStarted), execution);
                return Result.terminal(StopReason.TIMEOUT,
                        "模型调用网络异常或超时:" + safeMessage(networkOrTimeout));
            }
            Log.e(TAG, "[ModelCall] gateway threw " + cause.getClass().getSimpleName()
                    + ": " + safeMessage(cause) + " -> terminal=POLICY_HALT costMs="
                    + elapsedMillis(callStarted), execution);
            return Result.terminal(StopReason.POLICY_HALT,
                    "模型调用异常:" + safeMessage(cause));
        } finally {
            if (token != null) {
                token.removeAbortHook(abortHook);
            }
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    /**
     * 沿 cause 链向下查找 {@link ModelApiException.NetworkException} /
     * {@link ModelApiException.TimeoutException}。
     *
     * <p>网络/超时异常从 {@code ModelApiClient.post()} 抛出后,可能被中间层包装成外层异常
     * (如 LlmModelGateway 把非 RuntimeException 包成 IllegalStateException)。直接 instanceof
     * 只匹配顶层 cause,会漏判被包装的情况,导致模型决策阶段的网络/超时(时间类临时故障)误归类
     * 为 POLICY_HALT(应归 TIMEOUT)。沿链查找让映射对所有包装层级都健壮。深度上限 16 防自引用环。
     *
     * @return 链中第一个 NetworkException / TimeoutException;链中无则 null
     */
    private static ModelApiException findNetworkOrTimeout(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ModelApiException.NetworkException
                    || current instanceof ModelApiException.TimeoutException) {
                return (ModelApiException) current;
            }
            current = current.getCause();
        }
        return null;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    public static final class Result {
        private final ModelTurn turn;
        private final StopReason terminalReason;
        private final String message;

        private Result(ModelTurn turn, StopReason terminalReason, String message) {
            this.turn = turn;
            this.terminalReason = terminalReason;
            this.message = message;
        }

        public static Result success(ModelTurn turn) { return new Result(turn, null, ""); }
        public static Result terminal(StopReason reason, String message) {
            return new Result(null, reason, message);
        }
        public boolean isSuccess() { return turn != null; }
        public ModelTurn getTurn() { return turn; }
        public StopReason getTerminalReason() { return terminalReason; }
        public String getMessage() { return message; }
    }

    private static final class ModelThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "matrix-model-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
