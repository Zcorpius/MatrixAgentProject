package com.matrix.agent.task.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Test;

import com.matrix.agent.task.DynamicThreadPool;
import com.matrix.agent.task.capability.CapabilityDefinition;
import com.matrix.agent.task.capability.RiskLevel;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.task.tool.ToolResult.Status;

/**
 * ToolExecutor 在 ioPool 队列满 / Executor 拒绝时的契约测试。
 *
 * <p>评审发现共享池嵌套等待死锁,改为两独立池 + 显式拒绝处理。ToolExecutor submit 抛
 * RejectedExecutionException 时,resolveTerminal(REJECTED) 必须返回:
 * <ul>
 *   <li>写操作 → EXECUTION_UNKNOWN(命令可能未下发,但保守按"未知"提示);</li>
 *   <li>读操作 → CANCELLED(语义同 cancel,显式标 rejected)。</li>
 * </ul>
 */
public final class ToolExecutorRejectedExecutionTest {

    @Test
    public void rejectedWriteOperationReturnsExecutionUnknown() {
        // 用真实 reject 路径:pool 已 shutdown 后 submit 抛 RejectedExecutionException
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        pool.shutdown();
        ToolExecutor executor = new ToolExecutor(1, pool.asExecutorService());

        AgentRequest request = newRequest();
        CapabilityDefinition writeCap = fakeCap(true);
        ToolResult result = executor.execute(null, writeCap, request, fakeCall());

        assertEquals("写操作 REJECTED → EXECUTION_UNKNOWN", Status.EXECUTION_UNKNOWN, result.getStatus());
        assertTrue("消息含 REJECTED 标记", result.getMessage().contains("REJECTED"));
    }

    @Test
    public void rejectedReadOperationReturnsCancelled() {
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        pool.shutdown();
        ToolExecutor executor = new ToolExecutor(1, pool.asExecutorService());

        AgentRequest request = newRequest();
        CapabilityDefinition readCap = fakeCap(false);  // 读操作
        ToolResult result = executor.execute(null, readCap, request, fakeCall());

        assertEquals("读操作 REJECTED → CANCELLED", Status.CANCELLED, result.getStatus());
    }

    private static AgentRequest newRequest() {
        return AgentRequest.builder("test", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .timeoutMillis(10_000L)
                .build();
    }

    private static CapabilityDefinition fakeCap(boolean write) {
        return CapabilityDefinition.builder("fake",
                write ? RiskLevel.R1_LOW_RISK_WRITE : RiskLevel.R0_READ_ONLY)
                .description("test cap")
                .writeOperation(write)
                .timeoutMillis(5_000L)
                .verificationRequired(false)
                .build();
    }

    private static ToolCall fakeCall() {
        return new ToolCall("fake", Collections.<String, Object>emptyMap());
    }
}
