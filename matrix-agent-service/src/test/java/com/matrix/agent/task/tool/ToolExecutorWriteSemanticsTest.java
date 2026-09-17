package com.matrix.agent.task.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.capability.CapabilityDefinition;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.RiskLevel;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.CancellationToken;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * 写操作 timeout / cancel 语义边界测试。
 *
 * <p>评审发现:"ToolExecutor 对所有 capability 一视同仁,超时 / 取消时 future.cancel(true) +
 * 返回 TIMED_OUT / CANCELLED。但 CapabilityProvider 只有同步 execute() 接口,没有 abort /
 * 状态查询 / 请求幂等 ID——对未来接入 AIDL/Binder/厂商 SDK 的写操作,interrupt 不保证取消
 * 已发出的 IPC 命令,Provider 可能在 Runtime 已返回'已取消'后继续设置空调/座椅/导航。"
 *
 * <p>本测试覆盖 5 个场景:
 * <ol>
 *   <li>读操作 timeout → TIMED_OUT(现状不退化,行为兼容)</li>
 *   <li>写操作 + 不可 abort Provider + timeout → EXECUTION_UNKNOWN(不能宣称"已取消")</li>
 *   <li>写操作 + 不可 abort Provider + token.cancel → EXECUTION_UNKNOWN</li>
 *   <li>写操作 + 可 abort Provider + timeout → abortIfSupported 被调用 + EXECUTION_UNKNOWN</li>
 *   <li>写操作 + 不可中断 Provider(不响应 interrupt + 最终完成) → EXECUTION_UNKNOWN,
 *       即使 Provider 事后真的写入了车辆状态——Runtime 不能撒谎说"已取消"</li>
 * </ol>
 */
public final class ToolExecutorWriteSemanticsTest {
    private static final String READ_CAP = "test.read.slow";
    private static final String WRITE_CAP = "test.write.slow";

    /** 读操作超时仍返回 TIMED_OUT,行为不变。 */
    @Test
    public void readOnlyCapabilityTimeoutReturnsTimedOut() {
        CapabilityDefinition definition = CapabilityDefinition.builder(READ_CAP, RiskLevel.R0_READ_ONLY)
                .description("read-only slow").timeoutMillis(50L).build();
        // readOnly 默认(writeOperation 没显式设 true)
        CapabilityProvider provider = (request, call) -> {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "late-read", Collections.emptyMap(), true, 2_000L);
        };
        ToolExecutor executor = new ToolExecutor(1);
        AgentRequest request = AgentRequest.builder("slow read", Actor.DRIVER)
                .sessionId("read-session")
                .timeoutMillis(2_000L)
                .build();
        ToolCall call = new ToolCall(READ_CAP, Collections.emptyMap());

        ToolResult result = executor.execute(provider, definition, request, call);

        assertEquals(ToolResult.Status.TIMED_OUT, result.getStatus());
        executor.shutdown();
    }

    /** 写操作 + Provider 不支持 abort + 超时 → EXECUTION_UNKNOWN(不能宣称 CANCELLED / TIMED_OUT)。 */
    @Test
    public void writeCapabilityTimeoutReturnsExecutionUnknownWhenNotAbortable() {
        CapabilityDefinition definition = CapabilityDefinition.builder(WRITE_CAP, RiskLevel.R1_LOW_RISK_WRITE)
                .description("write slow").writeOperation(true).timeoutMillis(50L).build();
        CapabilityProvider provider = (request, call) -> {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "late-write", Collections.emptyMap(), true, 2_000L);
        };
        ToolExecutor executor = new ToolExecutor(1);
        AgentRequest request = AgentRequest.builder("slow write", Actor.DRIVER)
                .sessionId("write-session")
                .timeoutMillis(2_000L)
                .build();
        ToolCall call = new ToolCall(WRITE_CAP, Collections.emptyMap());

        ToolResult result = executor.execute(provider, definition, request, call);

        assertEquals("写操作超时必须返回 EXECUTION_UNKNOWN(Provider 可能仍在执行)",
                ToolResult.Status.EXECUTION_UNKNOWN, result.getStatus());
        assertNotEquals("写操作不能宣称 TIMED_OUT(命令可能已下发)",
                ToolResult.Status.TIMED_OUT, result.getStatus());
        assertNotEquals("写操作不能宣称 CANCELLED(命令可能已下发)",
                ToolResult.Status.CANCELLED, result.getStatus());
        executor.shutdown();
    }

    /** 写操作 + Provider 不支持 abort + 外部 token.cancel → EXECUTION_UNKNOWN。 */
    @Test
    public void writeCapabilityCancellationReturnsExecutionUnknownWhenNotAbortable()
            throws InterruptedException {
        CapabilityDefinition definition = CapabilityDefinition.builder(WRITE_CAP, RiskLevel.R1_LOW_RISK_WRITE)
                .description("write slow").writeOperation(true).timeoutMillis(5_000L).build();
        CancellationToken token = new CancellationToken();
        // 单独线程在 execute 启动后 100ms 触发 cancel
        Thread canceller = new Thread(() -> {
            try { Thread.sleep(100L); } catch (InterruptedException ie) { return; }
            token.cancel();
        }, "test-canceller");
        CapabilityProvider provider = (request, call) -> {
            canceller.start();
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "late-write", Collections.emptyMap(), true, 2_000L);
        };
        ToolExecutor executor = new ToolExecutor(1);
        AgentRequest request = AgentRequest.builder("cancel write", Actor.DRIVER)
                .sessionId("write-cancel-session")
                .timeoutMillis(10_000L)
                .cancellationToken(token)
                .build();
        ToolCall call = new ToolCall(WRITE_CAP, Collections.emptyMap());

        ToolResult result = executor.execute(provider, definition, request, call);

        assertEquals("写操作 + token cancel 必须返回 EXECUTION_UNKNOWN",
                ToolResult.Status.EXECUTION_UNKNOWN, result.getStatus());
        assertTrue("token 必须已被 cancel", token.isCancelled());
        canceller.join(1_000L);
        executor.shutdown();
    }

    /**
     * 写操作 + Provider 声明支持 abort + 超时 → ToolExecutor 调 abortIfSupported,
     * 返回 EXECUTION_UNKNOWN(abort 不保证成功,仍需 readback 二次确认)。
     */
    @Test
    public void abortableWriteProviderCallsAbortOnTimeout() {
        CapabilityDefinition definition = CapabilityDefinition.builder(WRITE_CAP, RiskLevel.R1_LOW_RISK_WRITE)
                .description("write slow").writeOperation(true).timeoutMillis(50L).build();
        AtomicInteger abortCount = new AtomicInteger();
        AtomicReference<String> abortedCommandId = new AtomicReference<>();
        CapabilityProvider provider = new CapabilityProvider() {
            @Override
            public ToolResult execute(AgentRequest request, ToolCall call) {
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                        "late-write", Collections.emptyMap(), true, 2_000L);
            }

            @Override
            public boolean isAbortable() { return true; }

            @Override
            public void abortIfSupported(String commandId) {
                abortCount.incrementAndGet();
                abortedCommandId.set(commandId);
            }
        };
        ToolExecutor executor = new ToolExecutor(1);
        AgentRequest request = AgentRequest.builder("abortable write", Actor.DRIVER)
                .sessionId("write-abort-session")
                .timeoutMillis(2_000L)
                .build();
        ToolCall call = new ToolCall(WRITE_CAP, Collections.emptyMap());

        ToolResult result = executor.execute(provider, definition, request, call);

        assertEquals("可 abort Provider 超时仍返回 EXECUTION_UNKNOWN(abort 不保证成功)",
                ToolResult.Status.EXECUTION_UNKNOWN, result.getStatus());
        assertEquals("abortIfSupported 必须被调用 1 次", 1, abortCount.get());
        assertEquals("abortIfSupported 必须传入 stepId 作为 commandId",
                call.getStepId(), abortedCommandId.get());
        executor.shutdown();
    }

    /**
     * 写操作 + Provider 完全不响应 interrupt(真实 IPC 场景:Binder 命令已发出,Java 中断无效)
     * + Provider 事后真的写入车辆状态 → Runtime 必须返回 EXECUTION_UNKNOWN,不能宣称"已取消"。
     *
     * <p>这是评审的核心场景:"Provider 可能在 Runtime 已返回'已取消'后,继续设置空调、座椅
     * 或开始导航"。EXECUTION_UNKNOWN 让 Agent / UI / 用户知道"命令状态未知",而不是错误的
     * "已取消"。
     */
    @Test
    public void uninterruptibleWriteProviderReturnsExecutionUnknownEvenIfProviderCompletes() {
        CapabilityDefinition definition = CapabilityDefinition.builder(WRITE_CAP, RiskLevel.R1_LOW_RISK_WRITE)
                .description("write uninterruptible").writeOperation(true).timeoutMillis(50L).build();
        // Provider 模拟真实 IPC:在 LoopXXX 中 sleep + 不响应 interrupt + 最终完成设置车辆状态
        AtomicInteger providerCompleted = new AtomicInteger();
        CapabilityProvider provider = (request, call) -> {
            long end = System.currentTimeMillis() + 300L;
            while (System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(end - System.currentTimeMillis());
                } catch (InterruptedException ie) {
                    // 不响应 interrupt——真实 Binder 命令不会因为 Java 中断而取消
                }
            }
            providerCompleted.set(1);
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "vehicle state set", Collections.emptyMap(), true, 300L);
        };
        ToolExecutor executor = new ToolExecutor(1);
        AgentRequest request = AgentRequest.builder("uninterruptible write", Actor.DRIVER)
                .sessionId("write-uninterruptible-session")
                .timeoutMillis(2_000L)
                .build();
        ToolCall call = new ToolCall(WRITE_CAP, Collections.emptyMap());

        ToolResult result = executor.execute(provider, definition, request, call);

        // 等待 worker 线程实际完成(给 Provider 时间真的"写入"车辆状态)
        try { Thread.sleep(500L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        assertEquals("Provider 不响应 interrupt,Runtime 必须返回 EXECUTION_UNKNOWN",
                ToolResult.Status.EXECUTION_UNKNOWN, result.getStatus());
        assertEquals("Provider 在 Runtime 超时后仍完成了写入(模拟真实 IPC)",
                1, providerCompleted.get());
        executor.shutdown();
    }
}
