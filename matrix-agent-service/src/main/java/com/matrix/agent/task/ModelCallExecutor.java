package com.matrix.agent.task;

import android.util.Log;

import com.matrix.agent.contract.CancellableModelCall;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;

import com.matrix.agent.contract.ModelApiException;

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
 *       + {@code call.abort()}（供本地/自定义 gateway 释放额外资源；远端 OkHttp transport
 *       同时由 request token 自己调用 {@code Call.cancel()}）；</li>
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
        return decide(gateway, request, com.matrix.agent.contract.ModelStreamSink.NONE);
    }

    public Result decide(ModelGateway gateway, ModelTurnRequest request,
            com.matrix.agent.contract.ModelStreamSink sink) {
        AgentRequest agentRequest = request.getAgentRequest();
        if (!agentRequest.getExecutionScope().rejection().isEmpty()) {
            return Result.terminal(StopReason.POLICY_HALT, "计划授权已失效");
        }
        if (!agentRequest.getExecutionScope().networkAllowed()
                && gateway.executionLane() == ModelGateway.ExecutionLane.NETWORK) {
            return Result.terminal(StopReason.POLICY_HALT, "计划未授权在线模型调用");
        }
        long budgetMillis = agentRequest.remainingMillis();
        if (budgetMillis <= 0) {
            Log.w(TAG, "[ModelCall] pre-call deadline already passed, terminal=TIMEOUT");
            return Result.terminal(StopReason.TIMEOUT, "调用模型前已超过截止时间");
        }

        if (!agentRequest.getExecutionScope().reserveModelCall()) {
            return Result.terminal(StopReason.POLICY_HALT, "模型调用预算已耗尽或授权失效");
        }
        long callStarted = System.nanoTime();
        Log.d(TAG, "[ModelCall] submit gateway=" + gateway.getClass().getSimpleName()
                + " budgetMs=" + budgetMillis
                + " req=" + agentRequest.getRequestId()
                + " mode=prepare+abort-hook");

        java.util.concurrent.atomic.AtomicBoolean streamClosed = new java.util.concurrent.atomic.AtomicBoolean();
        com.matrix.agent.contract.ModelStreamSink guarded = event -> {
            if (streamClosed.get() || agentRequest.isCancelled() || agentRequest.remainingMillis() <= 0) return;
            try { sink.accept(event); } catch (RuntimeException ignored) { }
        };
        CancellableModelCall call = sink == com.matrix.agent.contract.ModelStreamSink.NONE
                ? gateway.prepare(request) : gateway.prepareStreaming(request, guarded);
        CancellationToken token = agentRequest.getCancellationToken();

        Future<ModelTurn> future;
        try {
            ExecutorService worker = gateway.executionLane() == ModelGateway.ExecutionLane.SERIAL_LOCAL
                    ? localWorkers : networkWorkers;
            future = worker.submit(new Callable<ModelTurn>() {
                @Override
                public ModelTurn call() {
                    if (!agentRequest.getExecutionScope().rejection().isEmpty()
                            || agentRequest.remainingMillis() <= 0 || agentRequest.isCancelled()) {
                        throw new CancellationException("automatic authority no longer valid");
                    }
                    try {
                        ModelTurn turn = call.call();
                        guarded.accept(new com.matrix.agent.contract.ModelStreamEvent.Completed(turn.getFinishReason()));
                        return turn;
                    } catch (RuntimeException failure) {
                        guarded.accept(new com.matrix.agent.contract.ModelStreamEvent.Failed());
                        throw failure;
                    } finally { streamClosed.set(true); }
                }
            });
        } catch (RejectedExecutionException rejected) {
            // Local executor capacity is not a policy decision and no model call was made.
            streamClosed.set(true);
            try { call.abort(); }
            catch (RuntimeException abortFailure) {
                Log.w(TAG, "[ModelCall] prepared call abort failed after queue rejection", abortFailure);
            }
            Log.w(TAG, "[ModelCall] submit REJECTED req=" + agentRequest.getRequestId()
                    + " (ioPool 队列满 / executor 关闭)");
            return Result.terminal(StopReason.REJECTED, "模型调用队列已满");
        }

        // abort hook:cancel 触发时同步执行 future.cancel(true) + call.abort()
        // future.cancel(true) 让 worker thread 收到 interrupt；远端 HTTP 的 token hook 会在
        // 同一 cancel 事务中调用 OkHttp Call.cancel，call.abort() 留给其它 gateway 资源。
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
            // Repository's deadline timer can fire a few milliseconds before wall-clock
            // remainingMillis reaches zero. Treat that bounded race as deadline expiry.
            if (agentRequest.remainingMillis() <= 100L) {
                Log.w(TAG, "[ModelCall] deadline cancellation, terminal=TIMEOUT costMs="
                        + elapsedMillis(callStarted));
                return Result.terminal(StopReason.TIMEOUT, "模型调用超过请求截止时间");
            }
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
                if (agentRequest.remainingMillis() <= 0) {
                    return Result.terminal(StopReason.TIMEOUT, "模型执行前已超过请求截止时间");
                }
                Log.w(TAG, "[ModelCall] gateway cancelled (CancellationException), terminal=CANCELLED costMs="
                        + elapsedMillis(callStarted));
                return Result.terminal(StopReason.CANCELLED, "模型调用已取消(端侧)");
            }
            // The planner can wrap transport failures in IllegalStateException. Recover the
            // typed cause before choosing a terminal reason; HTTP 429 is not a safety veto.
            ModelApiException apiFailure = findModelApiException(cause);
            if (apiFailure != null) {
                StopReason reason = modelApiStopReason(apiFailure);
                Log.w(TAG, "[ModelCall] gateway " + apiFailure.getClass().getSimpleName()
                        + " (unwrapped from " + cause.getClass().getSimpleName() + ")"
                        + " -> terminal=" + reason + " costMs=" + elapsedMillis(callStarted));
                return Result.terminal(reason, "模型调用异常:" + safeMessage(apiFailure));
            }
            Log.e(TAG, "[ModelCall] gateway threw " + cause.getClass().getSimpleName()
                    + ": " + safeMessage(cause) + " -> terminal=MODEL_CALL_FAILED costMs="
                    + elapsedMillis(callStarted), execution);
            return Result.terminal(StopReason.MODEL_CALL_FAILED,
                    "模型调用异常:" + safeMessage(cause));
        } finally {
            streamClosed.set(true);
            if (token != null) {
                token.removeAbortHook(abortHook);
            }
        }
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static StopReason modelApiStopReason(ModelApiException error) {
        if (error instanceof ModelApiException.NetworkException) return StopReason.NETWORK_UNAVAILABLE;
        if (error instanceof ModelApiException.TimeoutException) return StopReason.TIMEOUT;
        if (error instanceof ModelApiException.RateLimitException) return StopReason.MODEL_RATE_LIMITED;
        return StopReason.MODEL_CALL_FAILED;
    }

    /** The provider's typed failure may be wrapped by the planner or gateway. */
    private static ModelApiException findModelApiException(Throwable cause) {
        Throwable current = cause;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current instanceof ModelApiException failure) return failure;
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
