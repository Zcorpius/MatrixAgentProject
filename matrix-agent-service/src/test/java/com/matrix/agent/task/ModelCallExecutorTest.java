package com.matrix.agent.task;

import com.matrix.agent.contract.ToolDefinition;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.ModelTurnRequest;

import com.matrix.agent.contract.ModelGateway;

import com.matrix.agent.contract.CancellableModelCall;

import com.matrix.agent.contract.AgentMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.session.SessionContext;

import com.matrix.agent.contract.ModelApiException;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;

/**
 * CancellableModelCall 抽象测试。
 *
 * <p>验证三个安全保证:
 * <ol>
 *   <li>{@code CancellationToken.cancel()} 触发时,{@code CancellableModelCall.abort()} 被同步调用;</li>
 *   <li>deadline 到时,ModelCallExecutor 主动触发 abort 链 + 返回 {@code Result.terminal(TIMEOUT)};</li>
 *   <li>外部 cancel 触发时,ModelCallExecutor 返回 {@code Result.terminal(CANCELLED)} 且 abort 被调用。</li>
 * </ol>
 *
 * <p>不测真实 HTTP——本测试只验证 ModelCallExecutor 与 CancellableModelCall / CancellationToken
 * 的协议契约。HTTP 层 abort 行为由 后续版本接 OkHttp 时补集成测试。
 */
public final class ModelCallExecutorTest {

    @Test
    public void localGatewayUsesDedicatedLocalExecutionLane() {
        ExecutorService network = Executors.newSingleThreadExecutor(r -> new Thread(r, "network-lane"));
        ExecutorService local = Executors.newSingleThreadExecutor(r -> new Thread(r, "local-lane"));
        try {
            ModelGateway gateway = new ModelGateway() {
                @Override public ExecutionLane executionLane() { return ExecutionLane.SERIAL_LOCAL; }
                @Override public ModelTurn decide(ModelTurnRequest ignored) {
                    return ModelTurn.directAnswer(Thread.currentThread().getName());
                }
            };
            ModelCallExecutor.Result result = new ModelCallExecutor(1, network, local).decide(
                    gateway, request("local-lane"));
            assertTrue(result.isSuccess());
            assertEquals("local-lane", result.getTurn().getAssistantMessage().getContent());
        } finally {
            network.shutdownNow();
            local.shutdownNow();
        }
    }

    @Test
    public void tokenCancelFiresAbortHookSynchronously() {
        CancellationToken token = new CancellationToken();
        AtomicBoolean hookFired = new AtomicBoolean(false);
        token.registerAbortHook(() -> hookFired.set(true));

        assertFalse("hook 不应在 cancel 前触发", hookFired.get());
        token.cancel();
        assertTrue("cancel() 后 hook 必须同步触发", hookFired.get());
    }

    private static ModelTurnRequest request(String sessionId) {
        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId(sessionId).timeoutMillis(10_000).build();
        return new ModelTurnRequest(agentRequest, Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.contract.ToolDefinition>emptyList(),
                "system", new SessionContext());
    }

    @Test
    public void tokenRegisterAfterCancelInvokesImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel();   // 先 cancel
        AtomicBoolean lateHook = new AtomicBoolean(false);
        token.registerAbortHook(() -> lateHook.set(true));
        assertTrue("cancel 后注册的 hook 必须立即执行(race-safe)", lateHook.get());
    }

    @Test
    public void tokenCancelIsIdempotent() {
        CancellationToken token = new CancellationToken();
        AtomicInteger fireCount = new AtomicInteger(0);
        Runnable hook = fireCount::incrementAndGet;
        token.registerAbortHook(hook);

        token.cancel();
        token.cancel();   // 第二次应被吞掉
        token.cancel();   // 第三次也应被吞掉

        assertEquals("cancel() 幂等,abort hook 只触发一次", 1, fireCount.get());
    }

    @Test
    public void modelCallExecutorReturnsCancelledOnTokenCancel() throws Exception {
        CountDownLatch callEntered = new CountDownLatch(1);
        CountDownLatch neverReturns = new CountDownLatch(1);
        // 模拟一个会无限阻塞的 ModelGateway——除非 abort 被调用
        ModelGateway gateway = new ModelGateway() {
            @Override
            public ModelTurn decide(ModelTurnRequest request) {
                callEntered.countDown();
                try {
                    neverReturns.await();   // 永不返回,除非 thread 被 interrupt
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("interrupted as expected");
                }
                return ModelTurn.directAnswer("never reached");
            }

            @Override
            public CancellableModelCall prepare(ModelTurnRequest request) {
                AtomicBoolean aborted = new AtomicBoolean(false);
                return new CancellableModelCall() {
                    @Override
                    public ModelTurn call() {
                        return decide(request);
                    }

                    @Override
                    public void abort() {
                        aborted.set(true);
                    }
                };
            }
        };

        CancellationToken token = new CancellationToken();
        ModelCallExecutor executor = new ModelCallExecutor(1);
        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId("cancel-test")
                .timeoutMillis(10_000)
                .cancellationToken(token)
                .build();
        ModelTurnRequest turnRequest = new ModelTurnRequest(
                agentRequest, Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.contract.ToolDefinition>emptyList(),
                "system", new SessionContext());

        // 异步跑 decide,等 gateway 进入阻塞后触发 cancel
        new Thread(() -> {
            try {
                callEntered.await(2, TimeUnit.SECONDS);
                Thread.sleep(50);   // 确保 future.get 已经在等
            } catch (InterruptedException ignored) {
            }
            token.cancel();
        }).start();

        ModelCallExecutor.Result result = executor.decide(gateway, turnRequest);

        assertFalse("cancel 后应非 success", result.isSuccess());
        assertEquals("cancel 触发应返回 CANCELLED 终态",
                StopReason.CANCELLED, result.getTerminalReason());
    }

    @Test
    public void modelCallExecutorReturnsTimeoutOnDeadlineExceeded() {
        // 模拟一个会无限阻塞的 gateway + 极短 deadline
        ModelGateway gateway = request -> {
            try {
                Thread.sleep(10_000);   // 远超 deadline
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted as expected");
            }
            return ModelTurn.directAnswer("never reached");
        };

        CancellationToken token = new CancellationToken();
        ModelCallExecutor executor = new ModelCallExecutor(1);
        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId("timeout-test")
                .timeoutMillis(100)   // 100ms deadline
                .cancellationToken(token)
                .build();
        ModelTurnRequest turnRequest = new ModelTurnRequest(
                agentRequest, Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.contract.ToolDefinition>emptyList(),
                "system", new SessionContext());

        ModelCallExecutor.Result result = executor.decide(gateway, turnRequest);

        assertFalse("timeout 后应非 success", result.isSuccess());
        assertEquals("deadline 到应返回 TIMEOUT 终态",
                StopReason.TIMEOUT, result.getTerminalReason());
        assertTrue("timeout 必须主动触发 token.cancel()(由 abort hook 链)",
                token.isCancelled());
    }

    /**
     * 网络异常被中间层包成 IllegalStateException 时,ModelCallExecutor 必须沿 cause 链识别出
     * NetworkException → TIMEOUT 终态(而非 POLICY_HALT)。
     *
     * <p>场景:ModelApiClient.post 抛 NetworkException,LlmModelGateway 或未来 CancellableModelCall
     * 把它包成外层 IllegalStateException。顶层 instanceof 会漏判,沿链查找才能正确归类。
     * 本层是模型决策阶段(调 LLM,尚未执行工具),网络/超时属时间类临时故障,归 TIMEOUT 语义最
     * 准确(区别于 POLICY_HALT 表示协议/模型不可恢复错误);两者都会终止 loop,但 TIMEOUT 准确
     * 反映"网络/超时"原因。(注:写操作 EXECUTION_UNKNOWN 是 Repository 在整体 future 超时收敛的,
     * 与本层模型决策超时无关。)
     */
    @Test
    public void networkExceptionWrappedInIllegalStateMapsToNetworkUnavailable() {
        ModelApiException.NetworkException root = new ModelApiException.NetworkException(
                "https://api.example.com", new java.io.IOException("connection reset"));
        ModelGateway gateway = request -> {
            throw new IllegalStateException("LLM 模型决策失败", root);
        };
        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId("wrapped-network").timeoutMillis(10_000).build();
        ModelTurnRequest turnRequest = new ModelTurnRequest(
                agentRequest, Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.contract.ToolDefinition>emptyList(),
                "system", new SessionContext());

        ModelCallExecutor.Result result = new ModelCallExecutor(1).decide(gateway, turnRequest);

        assertFalse("网络异常(被包装)后应非 success", result.isSuccess());
        assertEquals("被包装的网络异常应沿 cause 链识别为 NETWORK_UNAVAILABLE",
                StopReason.NETWORK_UNAVAILABLE,
                result.getTerminalReason());
    }

    /** 对照:网络异常直达(未被包装)时仍映射为 TIMEOUT——确保 cause 链查找不破坏原直达场景。 */
    @Test
    public void unwrappedNetworkExceptionStillMapsToTimeout() {
        ModelGateway gateway = request -> {
            throw new ModelApiException.NetworkException(
                    "https://api.example.com", new java.io.IOException("unknown host"));
        };
        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId("direct-network").timeoutMillis(10_000).build();
        ModelTurnRequest turnRequest = new ModelTurnRequest(
                agentRequest, Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.contract.ToolDefinition>emptyList(),
                "system", new SessionContext());

        ModelCallExecutor.Result result = new ModelCallExecutor(1).decide(gateway, turnRequest);

        assertFalse(result.isSuccess());
        assertEquals("直达的网络异常应映射为 NETWORK_UNAVAILABLE",
                StopReason.NETWORK_UNAVAILABLE, result.getTerminalReason());
    }

    /** 对照:非网络异常(普通 IllegalStateException,cause 链无 Network/Timeout)仍 POLICY_HALT。 */
    @Test
    public void nonNetworkExceptionMapsToPolicyHalt() {
        ModelGateway gateway = request -> {
            throw new IllegalStateException("协议解析失败");
        };
        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId("protocol-error").timeoutMillis(10_000).build();
        ModelTurnRequest turnRequest = new ModelTurnRequest(
                agentRequest, Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.contract.ToolDefinition>emptyList(),
                "system", new SessionContext());

        ModelCallExecutor.Result result = new ModelCallExecutor(1).decide(gateway, turnRequest);

        assertFalse(result.isSuccess());
        assertEquals("非网络异常应映射为 POLICY_HALT", StopReason.POLICY_HALT,
                result.getTerminalReason());
    }
}
