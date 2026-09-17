package com.matrix.agent.model;

import com.matrix.agent.task.AgentMessage;
import com.matrix.agent.task.CancellableModelCall;
import com.matrix.agent.task.FinishReason;
import com.matrix.agent.task.ModelTurn;
import com.matrix.agent.task.ModelTurnRequest;
import com.matrix.agent.task.capability.ToolDefinition;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.data.session.SessionContext;
import com.matrix.agent.ondevice.GenerationResult;
import com.matrix.agent.ondevice.MnnLoadOptions;
import com.matrix.agent.ondevice.OnDeviceFinishReason;
import com.matrix.agent.ondevice.OnDeviceLlm;
import com.matrix.agent.ondevice.OnDeviceLlmFactory;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * OnDeviceModelGateway 并发契约单测（Mock OnDeviceLlm，不需真机/MNN）。
 *
 * <p>覆盖八轮评审的核心并发场景：
 * <ul>
 *   <li>cancelChecker 中断 generate（CANCELLED terminal）</li>
 *   <li>retire 中断在途 generate（retired → cancelChecker）</li>
 *   <li>并发请求 sessionLock 串行（不并发调 native）</li>
 *   <li>abort per-call（排队 B 不误伤推理中 A）</li>
 *   <li>lease 引用计数（retire 后 awaitDrained 归零）</li>
 *   <li>mapResult CANCELLED → CancellationException（不 NONE/PROTOCOL_ERROR）</li>
 * </ul>
 */
public class OnDeviceModelGatewayConcurrencyTest {

    private OnDeviceModelGateway gateway;
    private MockOnDeviceLlm mockLlm;

    @Before
    public void setUp() {
        mockLlm = new MockOnDeviceLlm();
        gateway = new OnDeviceModelGateway(mockLlm, "test", 512);
    }

    /** 构造最小 ModelTurnRequest（1 个 user message + 1 个 tool）。 */
    private ModelTurnRequest newRequest() {
        AgentRequest agentRequest = new AgentRequest("把温度调到24度", Actor.DRIVER);
        List<AgentMessage> conv = new ArrayList<>();
        conv.add(AgentMessage.system("你是车机助手"));
        conv.add(AgentMessage.user("把温度调到24度"));
        List<ToolDefinition> tools = Collections.singletonList(
                new ToolDefinition("climate_set", "climate.set", "设置温度",
                        Collections.emptyList(), null));
        return new ModelTurnRequest(agentRequest, conv, tools, "你是车机助手",
                new SessionContext());
    }

    // ====== 测试 1：cancel 中断 generate → CancellationException ======

    @Test(timeout = 10000)
    public void cancelDuringGenerateThrowsCancellationException() throws Exception {
        mockLlm.generateDelayMs = 5000; // generate 慢

        CancellableModelCall call = gateway.prepare(newRequest());
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<ModelTurn> future = exec.submit(call::call);

        Thread.sleep(200); // 等 generate 开始
        call.abort(); // 取消

        try {
            future.get(5, TimeUnit.SECONDS);
            assertFalse("应抛 CancellationException", true);
        } catch (Exception e) {
            // 期望 CancellationException（或其包装）
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue("应为 CancellationException，实际: " + cause.getClass().getName(),
                    cause instanceof java.util.concurrent.CancellationException);
        }
        exec.shutdownNow();
    }

    // ====== 测试 2：retire 中断在途 generate ======

    @Test(timeout = 10000)
    public void retireInterruptsInFlightGenerate() throws Exception {
        mockLlm.generateDelayMs = 5000;

        CancellableModelCall call = gateway.prepare(newRequest());
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<ModelTurn> future = exec.submit(call::call);

        Thread.sleep(200); // 等 generate 开始
        gateway.retire(); // 退役（设 retired=true）

        try {
            future.get(5, TimeUnit.SECONDS);
            assertFalse("retire 应中断 generate", true);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            assertTrue("retire 应导致 CancellationException，实际: " + cause.getClass().getName(),
                    cause instanceof java.util.concurrent.CancellationException);
        }
        exec.shutdownNow();
    }

    // ====== 测试 3：并发请求 sessionLock 串行 ======

    @Test(timeout = 15000)
    public void concurrentRequestsAreSerialized() throws Exception {
        mockLlm.generateDelayMs = 300;
        AtomicInteger concurrency = new AtomicInteger(0);
        AtomicInteger maxConcurrency = new AtomicInteger(0);
        mockLlm.onGenerateStart = () -> {
            int c = concurrency.incrementAndGet();
            maxConcurrency.accumulateAndGet(c, Math::max);
        };
        mockLlm.onGenerateEnd = () -> concurrency.decrementAndGet();

        int n = 3;
        ExecutorService exec = Executors.newFixedThreadPool(n);
        List<Future<ModelTurn>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(exec.submit(() -> gateway.decide(newRequest())));
        }
        for (Future<ModelTurn> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        exec.shutdown();
        assertEquals("MNN session 不允许并发 generate，最大并发应为 1", 1, maxConcurrency.get());
    }

    // ====== 测试 4：abort 排队 B 不误伤推理中 A ======

    @Test(timeout = 10000)
    public void abortQueuedBDoesNotCancelRunningA() throws Exception {
        mockLlm.generateDelayMs = 1000;

        CountDownLatch aStarted = new CountDownLatch(1);
        mockLlm.onGenerateStart = aStarted::countDown;

        CancellableModelCall callA = gateway.prepare(newRequest());
        CancellableModelCall callB = gateway.prepare(newRequest());

        ExecutorService exec = Executors.newFixedThreadPool(2);
        Future<ModelTurn> futureA = exec.submit(callA::call);

        assertTrue("A 应先开始 generate", aStarted.await(2, TimeUnit.SECONDS));

        // B 在排队（sessionLock 被 A 持有），abort B
        Future<ModelTurn> futureB = exec.submit(callB::call);
        Thread.sleep(100);
        callB.abort(); // abort 排队的 B

        // A 不应被 B 的 abort 影响
        mockLlm.assertNativeCancelNotCalled(); // B 的 abort 不应触发 nativeCancel

        ModelTurn turnA = futureA.get(5, TimeUnit.SECONDS);
        assertTrue("A 应正常完成", turnA != null);

        exec.shutdownNow();
    }

    // ====== 测试 5：lease 归零后 awaitDrained 返回 true ======

    @Test(timeout = 10000)
    public void leaseDrainsToZeroAfterGenerateCompletes() throws Exception {
        mockLlm.generateDelayMs = 200;

        gateway.decide(newRequest()); // 同步跑一轮

        assertTrue("generate 完成后 lease 应归零", gateway.isDrained());
        assertTrue("awaitDrained 应立即返回 true", gateway.awaitDrained(1000));
    }

    // ====== 测试 6：mapResult CANCELLED 不返回 NONE ======

    @Test(timeout = 5000)
    public void cancelledResultDoesNotReturnNone() {
        // 直接测：cancelChecker=true 时 generate 返回 CANCELLED → mapResult 抛 CancellationException
        mockLlm.forceCancelled = true; // 模拟 generate 返回 CANCELLED

        try {
            gateway.decide(newRequest());
            assertFalse("CANCELLED 不应返回 ModelTurn（应抛 CancellationException）", true);
        } catch (java.util.concurrent.CancellationException e) {
            // 期望
        }
    }

    // ====== Mock OnDeviceLlm ======

    private static class MockOnDeviceLlm implements OnDeviceLlm {
        volatile long generateDelayMs = 0;
        volatile boolean cancelCheckerResult = false;
        volatile boolean forceCancelled = false;
        Runnable onGenerateStart = () -> {};
        Runnable onGenerateEnd = () -> {};
        private final AtomicBoolean nativeCancelCalled = new AtomicBoolean(false);

        @Override
        public GenerationResult generate(String messagesJson, String toolsJson,
                int maxNewTokens, BooleanSupplier cancelChecker) {
            if (forceCancelled) {
                return new GenerationResult("", OnDeviceFinishReason.CANCELLED, 0, 0, 0, 0, 0, null);
            }
            onGenerateStart.run();
            try {
                long elapsed = 0;
                while (elapsed < generateDelayMs) {
                    if (cancelChecker.getAsBoolean()) {
                        onGenerateEnd.run();
                        return new GenerationResult("", OnDeviceFinishReason.CANCELLED,
                                0, 0, 0, 0, 0, null);
                    }
                    Thread.sleep(10);
                    elapsed += 10;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                onGenerateEnd.run();
                return new GenerationResult("", OnDeviceFinishReason.CANCELLED,
                        0, 0, 0, 0, 0, null);
            }
            onGenerateEnd.run();
            // 模拟正常生成（含 tool call）
            return new GenerationResult(
                    "<tool_call>\n{\"name\": \"climate_set\", \"arguments\": {\"temp\": 24}}\n</tool_call>",
                    OnDeviceFinishReason.STOP, 10, 5, 100, 200, 50, null);
        }

        @Override
        public int countTokens(String messagesJson, String toolsJson) {
            return 100; // 固定值
        }

        @Override
        public int maxAllTokens() {
            return 2048;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void reset() {}

        @Override
        public void cancel() {
            nativeCancelCalled.set(true);
        }

        @Override
        public void close() {}

        void assertNativeCancelNotCalled() {
            assertFalse("nativeCancel 不应被排队任务的 abort 触发", nativeCancelCalled.get());
        }
    }
}
