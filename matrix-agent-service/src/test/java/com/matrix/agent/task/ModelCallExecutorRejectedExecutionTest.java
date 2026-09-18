package com.matrix.agent.task;

import com.matrix.agent.contract.ToolDefinition;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.ModelTurnRequest;

import com.matrix.agent.contract.ModelGateway;

import com.matrix.agent.contract.AgentMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Test;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.contract.ToolCall;

/**
 * ModelCallExecutor 在 ioPool 队列满 / Executor 拒绝时的契约测试。
 *
 * <p>评审发现共享池嵌套等待死锁,改为两独立池 + 显式拒绝处理。ModelCallExecutor submit 抛
 * RejectedExecutionException 时,返回 {@code Result.terminal(POLICY_HALT, "...")},让
 * AgentEngine 收到 POLICY_HALT 后立刻退出 Loop,不重试。
 */
public final class ModelCallExecutorRejectedExecutionTest {

    @Test
    public void rejectedSubmitReturnsPolicyHaltTerminal() {
        // 构造已 shutdown 的 pool——任何 submit 都会抛 RejectedExecutionException
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        pool.shutdown();
        ModelCallExecutor executor = new ModelCallExecutor(1, pool.asExecutorService());

        AgentRequest agentRequest = AgentRequest.builder("test", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .timeoutMillis(10_000L)
                .build();
        ModelTurnRequest request = new ModelTurnRequest(
                agentRequest,
                Collections.<AgentMessage>emptyList(),
                Collections.<com.matrix.agent.contract.ToolDefinition>emptyList(),
                "system prompt",
                new SessionContext());
        ModelGateway gateway = request1 -> ModelTurn.directAnswer("ignored");

        ModelCallExecutor.Result result = executor.decide(gateway, request);

        assertTrue("REJECTED 应非 success", !result.isSuccess());
        assertEquals("REJECTED → POLICY_HALT(立即退出 Loop)",
                StopReason.POLICY_HALT, result.getTerminalReason());
        assertTrue(result.getMessage().contains("拒绝"));
    }

    /** helper 被某些 ModelTurn 工厂使用,保留作为后续扩展锚点。 */
    @SuppressWarnings("unused")
    private static ToolCall unusedToolCallRef() { return null; }
}
