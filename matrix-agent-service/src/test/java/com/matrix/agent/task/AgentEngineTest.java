package com.matrix.agent.task;
import com.matrix.agent.task.compress.*;
import com.matrix.agent.task.redact.*;
import com.matrix.agent.task.steer.*;

import com.matrix.agent.demo.DemoModelGateway;

import com.matrix.agent.contract.SummaryProvider;

import com.matrix.agent.identity.ExplicitIntentConstraints;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.ModelTurnRequest;

import com.matrix.agent.contract.ModelGateway;

import com.matrix.agent.contract.FinishReason;

import com.matrix.agent.contract.AgentMessage;

import com.matrix.agent.task.capability.CapabilityDefinition;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.capability.RiskLevel;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.task.tool.ToolResult;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

public final class AgentEngineTest {
    private AgentEngine engine;
    private InMemoryMemoryStore memoryStore;
    private MockCapabilityProvider provider;
    private CapabilityRegistry registry;
    private SessionManager sessionManager;
    private SessionLockManager sessionLockManager;
    private ToolExecutor toolExecutor;
    private ModelCallExecutor modelCallExecutor;

    @Before
    public void setUp() {
        memoryStore = new InMemoryMemoryStore();
        registry = CapabilityRegistry.createDemoRegistry();
        provider = new MockCapabilityProvider(memoryStore);
        sessionManager = new SessionManager();
        sessionLockManager = new SessionLockManager();
        toolExecutor = new ToolExecutor(4);
        modelCallExecutor = new ModelCallExecutor(2);
        engine = createEngine(new DemoModelGateway(), provider);
    }

    @Test
    public void multiStepTaskExecutesAndVerifiesBothSteps() {
        AgentOutcome outcome = engine.execute(new AgentRequest(
                "把主驾温度调到24度，然后导航回家",
                Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(StopReason.NO_TOOL_CALL, outcome.getStopReason());
        assertEquals(2, outcome.getResults().size());
        assertTrue(outcome.getResults().get(0).isVerified());
        assertTrue(outcome.getResults().get(1).isVerified());
    }

    /**
     * OpenAI Native 多轮 Tool Calling 端到端 happy path。
     *
     * <p>模拟 OpenAI Native LLM 在 Agent Loop 中的典型多轮行为——区别于 {@link DemoModelGateway}
     * 的"一次性返回所有 tool_calls",本测试模拟真 LLM 每轮看 observation 后才决定下一步:
     * <ol>
     *   <li>第 1 轮 decide:conversation 只有 user → 返回 1 个 tool_call</li>
     *   <li>第 2 轮 decide:conversation 含 tool_result → directAnswer 结束</li>
     * </ol>
     *
     * <p>验证项:
     * <ul>
     *   <li>iterations.size() == 2(真正跑了 2 轮,不是 1 轮出全部 tool_calls)</li>
     *   <li>iteration[0]:1 个 tool_call + 1 个 observation</li>
     *   <li>iteration[1]:无 tool_call、无 observation(directAnswer)</li>
     *   <li>StopReason.NO_TOOL_CALL + FinalState.SUCCEEDED</li>
     * </ul>
     *
     * <p>本测试是核心可执行文档——证明 LlmModelGateway.useOpenAiNative 路径
     * (production code 已存在)在 Agent Loop 中跑多轮端到端正确。
     */
    @Test
    public void openAiNativeMultiTurnLoopEndsOnDirectAnswerAfterToolResult() {
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "DRIVER", "temperature", 24));
        ModelGateway gateway = oneShot(call, "openai-native-multiturn");

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("把主驾温度调到24度", Actor.DRIVER)
                        .sessionId("oai-multi").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(StopReason.NO_TOOL_CALL, outcome.getStopReason());

        List<AgentIteration> iterations = outcome.getTrajectory().getIterations();
        assertEquals("OpenAI Native 多轮应跑 2 轮(第 1 轮 tool_call,第 2 轮 directAnswer)",
                2, iterations.size());
        assertEquals("第 1 轮应触发 1 个 tool_call",
                1, iterations.get(0).getToolCalls().size());
        assertEquals("第 1 轮应有 1 个 observation",
                1, iterations.get(0).getObservations().size());
        assertTrue("第 2 轮应无 tool_call(directAnswer)",
                iterations.get(1).getToolCalls().isEmpty());
        assertTrue("第 2 轮应无 observation",
                iterations.get(1).getObservations().isEmpty());
    }

    /**
     * 测试 1:第 2 轮 LLM 调用前(L161 cancel 检查点)触发 cancel。
     *
     * <p>场景:第 1 轮 tool_call 已成功执行,然后外部 cancel。第 2 轮 iteration 开始时
     * L161 检查到 cancel,直接跳出——不调 LLM,不进入 Tool 循环。
     *
     * <p>注:gateway.decide 在第 2 轮被调用前的 iteration 顶部检查到 cancel——本测试通过
     * gateway 第 1 轮正常返回 + CapabilityProvider mock 在第 1 个 tool 完成后 cancel 模拟。
     * 第 2 轮 iteration 开始时 L161 拦截。
     */
    @Test
    public void agentLoopStopsOnCancelBeforeLlmCall() {
        CancellationToken token = new CancellationToken();
        ToolCall firstCall = new ToolCall("vehicle.info.get_battery", args());
        // 第 1 轮 gateway 返回 tool_call,第 2 轮(若执行)返回 directAnswer
        ModelGateway gateway = req -> {
            if (hasToolResult(req.getConversation())) {
                return ModelTurn.directAnswer("second turn never reached");
            }
            return ModelTurn.ofToolCalls(Collections.singletonList(firstCall), "first turn");
        };
        // CapabilityProvider mock:第 1 个 tool 执行成功 + 立即 cancel(模拟外部 cancel)
        CapabilityProvider cancelingProvider = (req, call) -> {
            ToolResult success = new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "ok", Collections.<String, Object>emptyMap(), true, 5);
            token.cancel();
            return success;
        };

        AgentOutcome outcome = createEngine(gateway, cancelingProvider)
                .execute(AgentRequest.builder("查电量", Actor.DRIVER)
                        .sessionId("cancel-pre-llm")
                        .cancellationToken(token)
                        .build());

        assertEquals(StopReason.CANCELLED, outcome.getStopReason());
        assertEquals(TaskState.CANCELLED, outcome.getFinalState());
        // 第 1 轮 tool 已成功执行(observations 已写入),第 2 轮 L161 拦截未跑 LLM
        assertEquals(1, outcome.getTrajectory().getIterations().size());
        assertEquals(1, outcome.getResults().size());
        assertTrue(outcome.getResults().get(0).isSuccess());
    }

    /**
     * 测试 2:per-tool-call checkpoint(L269 内循环顶部)触发 cancel。
     *
     * <p>场景:gateway 第 1 轮返回 2 个 tool_call,Provider mock 在第 1 个 tool 执行完成后
     * 触发 cancel。第 2 个 tool 开始前 per-tool checkpoint 检查到 cancel,break toolCallLoop,
     * 第 2 个 tool 未执行。
     *
     * <p>这同时验证 pre-tool checkpoint(264 行)与 per-tool checkpoint(269 行内循环顶部)的
     * 协同——两个 checkpoint 在 production code 中作为 defense-in-depth 存在。
     */
    @Test
    public void agentLoopStopsOnCancelDuringToolIteration() {
        CancellationToken token = new CancellationToken();
        ToolCall firstCall = new ToolCall("vehicle.info.get_battery", args());
        ToolCall secondCall = new ToolCall("vehicle.info.get_tire_pressure", args());
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(
                java.util.Arrays.asList(firstCall, secondCall), "two tools");

        // 自定义 CapabilityProvider:第 1 个 tool 执行成功 + cancel token,第 2 个永远不应被调
        CapabilityProvider cancelingProvider = new CapabilityProvider() {
            private final java.util.concurrent.atomic.AtomicInteger invoked =
                    new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public ToolResult execute(AgentRequest request, ToolCall call) {
                int count = invoked.incrementAndGet();
                if (count == 1) {
                    token.cancel();   // 第 1 个 tool 完成后 cancel
                    return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                            "first ok", Collections.emptyMap(), true, 5);
                }
                throw new AssertionError("第 2 个 tool 不应执行(per-tool checkpoint 应已拦截)");
            }
        };

        AgentOutcome outcome = createEngine(gateway, cancelingProvider)
                .execute(AgentRequest.builder("查电量和胎压", Actor.DRIVER)
                        .sessionId("cancel-per-tool")
                        .cancellationToken(token)
                        .build());

        assertEquals(StopReason.CANCELLED, outcome.getStopReason());
        assertEquals(TaskState.CANCELLED, outcome.getFinalState());
        AgentIteration iter = outcome.getTrajectory().getIterations().get(0);
        assertEquals("第 1 轮应声明 2 个 tool_call",
                2, iter.getToolCalls().size());
        assertEquals("实际只执行了 1 个(per-tool checkpoint 后 break)",
                1, iter.getObservations().size());
        assertEquals(1, outcome.getResults().size());
    }

    @Test
    public void prohibitedCapabilityIsRejectedBeforeProvider() {
        AgentOutcome outcome = engine.execute(new AgentRequest("打开ADAS", Actor.DRIVER));

        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
        assertFalse(outcome.getResults().get(0).isVerified());
    }

    @Test
    public void passengerCannotWriteDriverZone() {
        AgentOutcome outcome = engine.execute(new AgentRequest(
                "把主驾温度调到24度",
                Actor.PASSENGER));

        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
    }

    @Test
    public void contextCanApplyPreviousTemperatureToPassenger() {
        AgentOutcome first = engine.execute(new AgentRequest("主驾空调24度", Actor.DRIVER));
        AgentOutcome second = engine.execute(new AgentRequest("副驾也一样", Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, first.getFinalState());
        assertEquals(TaskState.SUCCEEDED, second.getFinalState());
        assertEquals(24, second.getResults().get(0).getObservedState().get("passenger.temperature"));
    }

    @Test
    public void memoryIsWrittenAndReadBack() {
        AgentOutcome saved = engine.execute(new AgentRequest("记住我喜欢24度", Actor.DRIVER));
        AgentOutcome read = engine.execute(new AgentRequest("我喜欢多少度", Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, saved.getFinalState());
        assertEquals("24", memoryStore.getPreference(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "preferred_temperature"));
        assertEquals(TaskState.SUCCEEDED, read.getFinalState());
        // 修复后 Trajectory 走 AuditRedactor,memory.preference.* 的 message 被替换为占位符——
        // getResults() 派生自 trajectory,看到的也是脱敏版。原始值校验走 memoryStore 即可。
        assertEquals(AuditRedactor.MEMORY_MESSAGE_PLACEHOLDER,
                read.getResults().get(0).getMessage());
    }

    @Test
    public void multiStepTaskReportsPartialSuccess() {
        AgentOutcome outcome = engine.execute(new AgentRequest(
                "空调调到23度，然后导航到失败测试点",
                Actor.DRIVER));

        assertEquals(TaskState.PARTIALLY_SUCCEEDED, outcome.getFinalState());
        assertTrue(outcome.getResults().get(0).isSuccess());
        assertFalse(outcome.getResults().get(1).isSuccess());
    }

    @Test
    public void passengerAndDriverDoNotShareSessionContext() {
        AgentOutcome driver = engine.execute(new AgentRequest("主驾空调24度", Actor.DRIVER));
        AgentOutcome passenger = engine.execute(new AgentRequest("副驾也一样", Actor.PASSENGER));

        assertEquals(TaskState.SUCCEEDED, driver.getFinalState());
        assertEquals(TaskState.FAILED, passenger.getFinalState());
        assertEquals(ToolResult.Status.POLICY_REJECTED, passenger.getResults().get(0).getStatus());
    }

    @Test
    public void uppercaseDriverZoneCannotBypassPassengerPolicy() {
        ToolCall malicious = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "DRIVER", "temperature", 24));
        AgentOutcome outcome = createEngine(oneShot(malicious, "uppercase zone"), provider)
                .execute(new AgentRequest("test", Actor.PASSENGER));

        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
    }

    @Test
    public void missingZoneIsRejectedBeforeProvider() {
        ToolCall missingZone = new ToolCall("vehicle.climate.set_temperature",
                args("temperature", 24));
        AgentOutcome outcome = createEngine(oneShot(missingZone, "missing zone"), provider)
                .execute(new AgentRequest("test", Actor.DRIVER));

        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
    }

    @Test
    public void temperatureBoundaryRejectsValuesOutsideRange() {
        assertEquals(TaskState.SUCCEEDED,
                engine.execute(new AgentRequest("空调16度", Actor.DRIVER)).getFinalState());
        assertEquals(TaskState.SUCCEEDED,
                engine.execute(new AgentRequest("空调30度", Actor.DRIVER)).getFinalState());
        assertEquals(TaskState.FAILED,
                engine.execute(new AgentRequest("空调15度", Actor.DRIVER)).getFinalState());
        assertEquals(TaskState.FAILED,
                engine.execute(new AgentRequest("空调31度", Actor.DRIVER)).getFinalState());
    }

    @Test
    public void cancelledRequestDoesNotPlanOrExecute() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        AgentRequest request = AgentRequest.builder("空调24度", Actor.DRIVER)
                .sessionId("cancelled")
                .cancellationToken(token)
                .build();

        AgentOutcome outcome = engine.execute(request);

        assertEquals(TaskState.CANCELLED, outcome.getFinalState());
        assertEquals(StopReason.CANCELLED, outcome.getStopReason());
        assertTrue(outcome.getResults().isEmpty());
    }

    @Test
    public void mockCanExposeReadbackMismatch() {
        provider.failNextVehicleReadback();

        AgentOutcome outcome = engine.execute(new AgentRequest("空调24度", Actor.DRIVER));

        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertEquals(ToolResult.Status.VERIFICATION_FAILED, outcome.getResults().get(0).getStatus());
        assertFalse(outcome.getResults().get(0).isVerified());
    }

    @Test
    public void requestDeadlineStopsExecutionAfterSlowPlanning() {
        ModelGateway slowGateway = req -> {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ModelTurn.ofToolCalls(Collections.singletonList(
                    new ToolCall("knowledge.answer", args("question", "test"))), "slow");
        };
        AgentRequest request = AgentRequest.builder("test", Actor.DRIVER)
                .sessionId("timeout-session")
                .timeoutMillis(1L)
                .build();

        AgentOutcome outcome = createEngine(slowGateway, provider).execute(request);

        assertEquals(TaskState.TIMED_OUT, outcome.getFinalState());
        assertEquals(StopReason.TIMEOUT, outcome.getStopReason());
        assertTrue(outcome.getResults().isEmpty());
    }

    @Test
    public void differentSessionsCanExecuteConcurrently() throws Exception {
        ModelGateway gateway = knowledgeGateway();
        CapabilityProvider slowProvider = (request, call) -> {
            try {
                Thread.sleep(120L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "ok", Collections.emptyMap(), true, 120L);
        };
        AgentEngine concurrentEngine = createEngine(gateway, slowProvider);
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
        long started = System.nanoTime();
        try {
            java.util.concurrent.Future<AgentOutcome> driver = workers.submit(() -> concurrentEngine.execute(
                    AgentRequest.builder("driver", Actor.DRIVER).sessionId("driver-session").build()));
            java.util.concurrent.Future<AgentOutcome> passenger = workers.submit(() -> concurrentEngine.execute(
                    AgentRequest.builder("passenger", Actor.PASSENGER).sessionId("passenger-session").build()));
            assertEquals(TaskState.SUCCEEDED, driver.get().getFinalState());
            assertEquals(TaskState.SUCCEEDED, passenger.get().getFinalState());
        } finally {
            workers.shutdownNow();
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("Different sessions were serialized: " + elapsedMillis, elapsedMillis < 220L);
    }

    @Test
    public void sameSessionRemainsSerializedUnderContentionAndLockIsReleased() throws Exception {
        java.util.concurrent.atomic.AtomicInteger active = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxActive = new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway gateway = knowledgeGateway();
        CapabilityProvider checkingProvider = (request, call) -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "ok", Collections.emptyMap(), true, 5L);
        };
        AgentEngine concurrentEngine = createEngine(gateway, checkingProvider);
        java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<java.util.concurrent.Future<AgentOutcome>> futures = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < 40; index++) {
                futures.add(workers.submit(() -> concurrentEngine.execute(
                        AgentRequest.builder("same", Actor.DRIVER).sessionId("same-session").build())));
            }
            for (java.util.concurrent.Future<AgentOutcome> future : futures) {
                assertEquals(TaskState.SUCCEEDED, future.get().getFinalState());
            }
        } finally {
            workers.shutdownNow();
        }
        assertEquals(1, maxActive.get());
        assertEquals(0, sessionLockManager.entryCount());
    }

    @Test
    public void capabilityTimeoutIsEnforcedIndependentlyFromRequestDeadline() {
        registry.register(CapabilityDefinition.builder("test.slow", RiskLevel.R0_READ_ONLY)
                .description("slow test").timeoutMillis(30L).build());
        ToolCall slow = new ToolCall("test.slow", args());
        ModelGateway gateway = oneShot(slow, "slow tool");
        CapabilityProvider slowProvider = (request, call) -> {
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "late", Collections.emptyMap(), true, 1_000L);
        };
        long started = System.nanoTime();

        AgentOutcome outcome = createEngine(gateway, slowProvider).execute(
                AgentRequest.builder("slow", Actor.DRIVER).sessionId("slow-tool").timeoutMillis(2_000L).build());

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertEquals(ToolResult.Status.TIMED_OUT, outcome.getResults().get(0).getStatus());
        assertTrue("Tool timeout was not enforced: " + elapsedMillis, elapsedMillis < 300L);
    }

    @Test
    public void fractionalTemperatureIsRejectedInsteadOfTruncated() {
        ToolCall fractional = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 30.9d));
        AgentOutcome outcome = createEngine(oneShot(fractional, "fractional"), provider)
                .execute(new AgentRequest("test", Actor.DRIVER));

        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
    }

    // ===== Agent Loop 专属 =====

    @Test
    public void agentLoopTerminatesOnMaxIterations() {
        ToolCall call = new ToolCall("knowledge.answer", args("question", "x"));
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(Collections.singletonList(call), "loop");
        AgentEngine loopEngine = new AgentEngine(gateway, modelCallExecutor,
                new PolicyEngine(registry), registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor,
                new AgentBudget(3, 8, 8_000L, 8_000, 32_000));

        AgentOutcome outcome = loopEngine.execute(new AgentRequest("loop", Actor.DRIVER));

        assertEquals(StopReason.MAX_ITERATIONS, outcome.getStopReason());
        assertEquals(3, outcome.getTrajectory().getIterations().size());
        assertEquals(3, outcome.getTrajectory().getTotalToolCalls());
    }

    @Test
    public void agentLoopTerminatesOnMaxToolCalls() {
        ToolCall a = new ToolCall("knowledge.answer", args("question", "a"));
        ToolCall b = new ToolCall("knowledge.answer", args("question", "b"));
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(java.util.Arrays.asList(a, b), "loop");
        AgentEngine loopEngine = new AgentEngine(gateway, modelCallExecutor,
                new PolicyEngine(registry), registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor,
                new AgentBudget(8, 3, 8_000L, 8_000, 32_000));

        AgentOutcome outcome = loopEngine.execute(new AgentRequest("loop", Actor.DRIVER));

        assertEquals(StopReason.MAX_TOOL_CALLS, outcome.getStopReason());
        assertTrue("total tool calls must not exceed budget",
                outcome.getTrajectory().getTotalToolCalls() <= 3);
    }

    @Test
    public void agentLoopTerminatesOnTotalInputCharBudget() {
        ToolCall call = new ToolCall("knowledge.answer", args("question", "x"));
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(Collections.singletonList(call), "loop");
        AgentEngine loopEngine = new AgentEngine(gateway, modelCallExecutor,
                new PolicyEngine(registry), registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor,
                new AgentBudget(8, 8, 8_000L, 8_000, 16));

        AgentOutcome outcome = loopEngine.execute(new AgentRequest("loop", Actor.DRIVER));

        assertEquals(StopReason.BUDGET_EXHAUSTED, outcome.getStopReason());
        assertEquals(0, outcome.getTrajectory().getTotalToolCalls());
        assertEquals(TaskState.FAILED, outcome.getFinalState());
    }

    /**
     * #1 回归:并发执行时,每个任务的对话压缩必须使用各自的 AgentRequest,不串用。
     *
     * <p>#1 修复前 appendMessageWithBudget 读实例字段 currentRequest——同一 AgentEngine 并发跑
     * 不同 session 时,任务 A 的压缩可能读到任务 B 的 request(SummaryProvider 收到错的 request)。
     * 修复后 request 作为参数透传。本测试用记录型 SummaryProvider 捕获 compress 收到的 request,
     * 并发跑 driver/passenger,断言各 session 压缩记录到的 text 全属于本 session。
     */
    @Test
    public void concurrentCompressionDoesNotCrossUseRequest() throws Exception {
        java.util.concurrent.ConcurrentHashMap<String, java.util.List<String>> seen =
                new java.util.concurrent.ConcurrentHashMap<>();
        SummaryProvider recorder = (turns, request) -> {
            seen.computeIfAbsent(request.getSessionId(),
                    k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(request.getText());
            return "压缩摘要";
        };
        ConversationCompressor compressor = new ConversationCompressor(recorder);

        // 每轮 1 个 knowledge.answer tool_call + 400 chars content,让 conversation 稳定增长;
        // totalInputChars=3000(80%=2400) 远大于 system prompt,确保前几轮既不触发压缩也不
        // BUDGET_EXHAUSTED;第 4~5 轮 conversation 超 2400 触发压缩,此时 transaction 数 ≥ 4 →
        // toSummarize 非空 → summarize 被调 → 记录本次 compress 用的 request。
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(
                Collections.singletonList(new ToolCall("knowledge.answer", args("question", "q"))),
                repeatX(400));
        AgentEngine concurrentEngine = new AgentEngine(gateway, modelCallExecutor,
                new PolicyEngine(registry), registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor,
                new AgentBudget(10, 10, 60_000L, 8_000, 3_000), null,
                new AgentEngineConfiguration.Builder()
                        .conversationCompressor(compressor)
                        .build());

        java.util.concurrent.ExecutorService workers =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<AgentOutcome> driver = workers.submit(() -> concurrentEngine.execute(
                    AgentRequest.builder("driver-text", Actor.DRIVER)
                            .sessionId("driver-session").build()));
            java.util.concurrent.Future<AgentOutcome> passenger = workers.submit(() -> concurrentEngine.execute(
                    AgentRequest.builder("passenger-text", Actor.PASSENGER)
                            .sessionId("passenger-session").build()));
            driver.get();
            passenger.get();
        } finally {
            workers.shutdownNow();
        }

        assertFalse("压缩应被触发(summarize 应至少被调用一次)", seen.isEmpty());
        assertCompressionRequestBelongsToSession("driver-session", "driver-text", seen);
        assertCompressionRequestBelongsToSession("passenger-session", "passenger-text", seen);
    }

    private static void assertCompressionRequestBelongsToSession(String sessionId, String expectedText,
            java.util.Map<String, java.util.List<String>> seen) {
        // 必须断言该 session 真的进入了压缩路径(有记录)——之前 null 时 early-return 会让
        // "某 session 没触发压缩"静默通过,无法保证双会话都被实际验证。
        java.util.List<String> texts = seen.get(sessionId);
        assertNotNull("session " + sessionId + " 未触发压缩,测试未覆盖该会话", texts);
        for (String text : texts) {
            assertEquals("session " + sessionId + " 的对话压缩串用了别的 request 的 text",
                    expectedText, text);
        }
    }

    private static String repeatX(int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, 'x');
        return new String(arr);
    }

    /**
     * maxMessageChars 限制单条消息长度——超长 system prompt 会撑爆总字符预算。
     * 旧实现只把 maxMessageChars 喂给 ModelSanitizer/AuditRedactor,system/user 消息原样进 conversation。
     */
    @Test
    public void agentLoopTruncatesMessagesExceedingMaxMessageChars() {
        ToolCall call = new ToolCall("knowledge.answer", args("question", "x"));
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(Collections.singletonList(call), "trunc");
        // maxMessageChars=200,system prompt 远超此长度 → 必须截断
        AgentEngine loopEngine = new AgentEngine(gateway, modelCallExecutor,
                new PolicyEngine(registry), registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor,
                new AgentBudget(8, 8, 8_000L, 200, 32_000));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 1000; i++) huge.append("a");
        // 直接构造带超长文本的 request,Engine 内部 buildSystemPrompt 会拼上 request 文本
        AgentOutcome outcome = loopEngine.execute(new AgentRequest(huge.toString(), Actor.DRIVER));

        // 截断后总字符预算未耗尽,正常按 maxIterations/maxToolCalls 终止
        assertNotEquals("截断后不应被 BUDGET_EXHAUSTED 终止",
                StopReason.BUDGET_EXHAUSTED, outcome.getStopReason());
        assertTrue("应当执行到至少 1 个 iteration",
                outcome.getTrajectory().getIterations().size() >= 1);
    }

    /**
     * maxMessageCount 限制 conversation 消息条数——模型在字符预算内
     * 通过海量短消息无限堆叠的场景必须终止。旧实现无条数预算。
     */
    @Test
    public void agentLoopTerminatesOnMaxMessageCount() {
        // maxMessageCount=4:system + user = 2 条,iteration 1: assistant + tool = 2 条(已达上限)
        // iteration 2: assistant 无法加入 → BUDGET_EXHAUSTED。iteration 1 的 tool 已成功,
        // 因此最终是 PARTIALLY_SUCCEEDED 而非 FAILED,符合 computeFinalState 语义。
        ToolCall call = new ToolCall("knowledge.answer", args("question", "x"));
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(Collections.singletonList(call), "loop");
        AgentEngine loopEngine = new AgentEngine(gateway, modelCallExecutor,
                new PolicyEngine(registry), registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor,
                new AgentBudget(8, 8, 8_000L, 8_000, 32_000, 4));

        AgentOutcome outcome = loopEngine.execute(new AgentRequest("loop", Actor.DRIVER));

        assertEquals(StopReason.BUDGET_EXHAUSTED, outcome.getStopReason());
        // iteration 1 成功执行 1 个 tool,异常终止时已有部分成功 → PARTIALLY_SUCCEEDED
        assertEquals(TaskState.PARTIALLY_SUCCEEDED, outcome.getFinalState());
    }

    @Test
    public void policyCapabilityRejectionIsNotAppealable() {
        ToolCall adas = new ToolCall("vehicle.adas.set_enabled", args("enabled", true));
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(Collections.singletonList(adas), "adas");
        AgentEngine loopEngine = createEngine(gateway, provider);

        AgentOutcome outcome = loopEngine.execute(
                AgentRequest.builder("adas", Actor.DRIVER).sessionId("adas-session").build());

        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertFalse(outcome.getTrajectory().getIterations().isEmpty());
        for (AgentIteration iteration : outcome.getTrajectory().getIterations()) {
            assertEquals(1, iteration.getObservations().size());
            assertTrue("iteration " + iteration.getIteration()
                    + " observation should be capability-blocked",
                    iteration.getObservations().get(0).isCapabilityBlocked());
        }
    }

    @Test
    public void policyParameterRejectionAllowsModelRetry() {
        ModelGateway gateway = new ModelGateway() {
            private int attempt = 0;

            @Override
            public ModelTurn decide(ModelTurnRequest req) {
                if (hasToolResult(req.getConversation())) {
                    attempt++;
                    if (attempt == 1) {
                        return ModelTurn.ofToolCalls(Collections.singletonList(
                                new ToolCall("vehicle.climate.set_temperature",
                                        args("zone", "driver", "temperature", 24))), "retry");
                    }
                    return ModelTurn.directAnswer("done");
                }
                return ModelTurn.ofToolCalls(Collections.singletonList(
                        new ToolCall("vehicle.climate.set_temperature",
                                args("zone", "driver", "temperature", 50))), "invalid");
            }
        };

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(new AgentRequest("retry", Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(2, outcome.getResults().size());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
        assertTrue(outcome.getResults().get(1).isSuccess());
    }

    @Test
    public void trajectoryContainsStructuredFields() {
        AgentOutcome outcome = engine.execute(new AgentRequest("空调24度", Actor.DRIVER));
        Trajectory trajectory = outcome.getTrajectory();

        assertEquals(StopReason.NO_TOOL_CALL, trajectory.getStopReason());
        assertEquals(1, trajectory.getTotalToolCalls());
        assertEquals(1, trajectory.countSuccessfulToolCalls());
        assertTrue(trajectory.getDurationMillis() >= 0);

        AgentIteration iteration = trajectory.getIterations().get(0);
        assertEquals(1, iteration.getIteration());
        assertTrue(iteration.getDurationMillis() >= 0);
        assertEquals(1, iteration.getToolCalls().size());
        AgentIteration.ToolCallSnapshot snapshot = iteration.getToolCalls().get(0);
        assertEquals("vehicle.climate.set_temperature", snapshot.getCapabilityName());
        assertNotNull(snapshot.getToolCallId());
        assertEquals(1, iteration.getObservations().size());
        assertTrue(iteration.getObservations().get(0).isSuccess());
        assertEquals(1, iteration.getPolicyDecisions().size());
        assertTrue(iteration.getPolicyDecisions().get(0).isAllowed());
    }

    @Test
    public void observationFromFailedToolIsReturnedToModel() {
        ToolCall failing = new ToolCall("navigation.start_route",
                args("destination", "失败测试点"));
        ModelGateway gateway = new ModelGateway() {
            private boolean fellBack = false;

            @Override
            public ModelTurn decide(ModelTurnRequest req) {
                if (hasToolResult(req.getConversation())) {
                    if (!fellBack) {
                        fellBack = true;
                        return ModelTurn.ofToolCalls(Collections.singletonList(
                                new ToolCall("knowledge.answer", args("question", "fallback"))),
                                "fallback");
                    }
                    return ModelTurn.directAnswer("done");
                }
                return ModelTurn.ofToolCalls(Collections.singletonList(failing), "first");
            }
        };

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(new AgentRequest("fail-then-fallback", Actor.DRIVER));

        AgentIteration firstIteration = outcome.getTrajectory().getIterations().get(0);
        ToolObservation observation = firstIteration.getObservations().get(0);
        assertEquals(ToolResult.Status.EXECUTION_FAILED, observation.getResult().getStatus());
        assertTrue(observation.toToolContent().contains("EXECUTION_FAILED"));

        assertEquals(2, outcome.getResults().size());
        assertEquals(TaskState.PARTIALLY_SUCCEEDED, outcome.getFinalState());
    }

    @Test
    public void sessionManagerExpiresIdleSessionsAndEnforcesCapacity() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000L);
        SessionManager manager = new SessionManager(100L, 2, clock::get);
        manager.getOrCreate("one");
        manager.getOrCreate("two");
        manager.getOrCreate("three");
        assertEquals(2, manager.size());
        assertTrue(manager.getRecentTurns("one").isEmpty());

        clock.addAndGet(101L);
        assertEquals(0, manager.size());
    }

    /**
     * 副驾越权 zone(修复):MODEL_INFERRED 的 zone=DRIVER 应该归 PARAMETER 拒绝,
     * 可让模型换 zone=passenger 重试。模型/Demo 脑补的 zone 不代表用户明确意图,
     * 让模型重新推断是合理的。
     */
    @Test
    public void passengerCanRetryWithCorrectZoneAfterParameterRejection() {
        java.util.concurrent.atomic.AtomicInteger attempt = new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway gateway = req -> {
            int n = attempt.incrementAndGet();
            if (n == 1) {
                // 默认 provenance=MODEL_INFERRED(模型脑补)
                return ModelTurn.ofToolCalls(Collections.singletonList(
                        new ToolCall("vehicle.climate.set_temperature",
                                args("zone", "DRIVER", "temperature", 24))), "wrong-zone");
            }
            if (n == 2) {
                return ModelTurn.ofToolCalls(Collections.singletonList(
                        new ToolCall("vehicle.climate.set_temperature",
                                args("zone", "passenger", "temperature", 24))), "correct-zone");
            }
            return ModelTurn.directAnswer("done");
        };

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("副驾调温", Actor.PASSENGER)
                        .sessionId("pax-retry").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(2, outcome.getResults().size());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
        assertFalse(outcome.getResults().get(0).isVerified());
        assertTrue(outcome.getResults().get(1).isSuccess());
        assertEquals(24, outcome.getResults().get(1).getObservedState().get("passenger.temperature"));
        ToolObservation firstObs = outcome.getTrajectory().getIterations().get(0)
                .getObservations().get(0);
        assertFalse("PARAMETER rejection must not be capability-blocked",
                firstObs.isCapabilityBlocked());
    }

    /**
     * 副驾越权 zone(修复):USER_EXPLICIT 的 zone=DRIVER(用户原话明确要求改主驾)
     * 必须归 CAPABILITY 拒绝,不可上诉——不能擅自改 zone=passenger 帮用户执行没要求的动作。
     */
    @Test
    public void passengerExplicitDriverZoneIsCapabilityRejected() {
        Map<String, ToolCall.ArgumentProvenance> explicit =
                java.util.Collections.singletonMap("zone", ToolCall.ArgumentProvenance.USER_EXPLICIT);
        ToolCall explicitCall = ToolCall.withId("explicit-1",
                "vehicle.climate.set_temperature",
                args("zone", "DRIVER", "temperature", 24), explicit);
        ModelGateway gateway = oneShot(explicitCall, "explicit");

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("把主驾温度调到24度", Actor.PASSENGER)
                        .sessionId("pax-explicit").build());

        assertEquals(TaskState.FAILED, outcome.getFinalState());
        assertEquals(1, outcome.getResults().size());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
        ToolObservation obs = outcome.getTrajectory().getIterations().get(0)
                .getObservations().get(0);
        assertTrue("USER_EXPLICIT zone violation must be capability-blocked",
                obs.isCapabilityBlocked());
    }

    /**
     * 回归:ExplicitIntentConstraints 是 Runtime 受信任边界提取的——
     * 即使模型 adapter 路径不标 provenance(默认 MODEL_INFERRED),只要 AgentRequest
     * 提取到 explicitZone=DRIVER(用户原话"主驾"),Policy 仍然 CAPABILITY 拒绝。
     *
     * <p>这堵住了真实 LLM 路径的口子:副驾说"把主驾温度调到24度" → 模型听懂回 zone=driver
     * → adapter 不标 provenance → 没有 ExplicitIntentConstraints 时会被当模型脑补,
     * 让模型改 zone=passenger 重试 → 实际执行了用户没要求的副驾调温。
     */
    @Test
    public void passengerExplicitZoneFromAgentRequestBlocksModelInferredCall() {
        // 模拟真实 LLM adapter 路径:ToolCall 不标 provenance,默认 MODEL_INFERRED
        ToolCall modelInferred = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "DRIVER", "temperature", 24));
        ModelGateway gateway = oneShot(modelInferred, "model-inferred");

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("把主驾温度调到24度", Actor.PASSENGER)
                        .sessionId("pax-explicit-intent").build());

        // AgentRequest.Builder 内部自动调 ExplicitIntentConstraints.extractFrom(text)
        // "把主驾温度调到24度" 包含"主驾" → explicitZone=DRIVER
        assertEquals(TaskState.FAILED, outcome.getFinalState());
        ToolObservation obs = outcome.getTrajectory().getIterations().get(0)
                .getObservations().get(0);
        assertTrue("explicitZone must override model-inferred provenance",
                obs.isCapabilityBlocked());
    }

    /**
     * 漏场景 1:副驾明确要求"主驾调温",模型第一次直接返回 zone=passenger
     * (用模型自己脑补的合法区域替换用户原意)。原检查只在 targetZone=DRIVER 时生效,
     * 因此漏判——必须 PARAMETER 拒绝,让模型保持用户原目标 zone=driver 重试。
     */
    @Test
    public void passengerExplicitDriverRejectsModelReturnedPassengerFirstAttempt() {
        ToolCall modelReturned = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "passenger", "temperature", 24));
        ModelGateway gateway = oneShot(modelReturned, "model-passenger");

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("把主驾温度调到24度", Actor.PASSENGER)
                        .sessionId("pax-explicit-driver-model-passenger").build());

        // 用户明确 zone=DRIVER,模型返 zone=passenger → PARAMETER 拒绝(可让模型重试)
        ToolObservation obs = outcome.getTrajectory().getIterations().get(0)
                .getObservations().get(0);
        assertFalse("first attempt PARAMETER-rejected, not capability-blocked",
                obs.isCapabilityBlocked());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
    }

    /**
     * 端到端:副驾明确要求"主驾调温"。
     * <ol>
     *   <li>模型第一次返 passenger(脑补)→ PARAMETER 拒绝,让模型保持用户原目标重试</li>
     *   <li>模型重试用 driver(对齐 explicitZone)→ CAPABILITY 拒绝(driver 越权)</li>
     *   <li>最终 FAILED,不会执行任何一次实际调温</li>
     * </ol>
     * 这是评审 13.3.1 漏场景 1 的完整闭环:模型无法通过"自作主张改合法区域"
     * 绕过用户意图,也无法通过对齐用户原意后绕过越权检查。
     */
    @Test
    public void passengerExplicitDriverEndToEndRejectsBothWrongAndViolationRetry() {
        java.util.concurrent.atomic.AtomicInteger attempt = new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway gateway = req -> {
            int n = attempt.incrementAndGet();
            if (n == 1) {
                return ModelTurn.ofToolCalls(Collections.singletonList(
                        new ToolCall("vehicle.climate.set_temperature",
                                args("zone", "passenger", "temperature", 24))), "wrong-zone");
            }
            if (n == 2) {
                return ModelTurn.ofToolCalls(Collections.singletonList(
                        new ToolCall("vehicle.climate.set_temperature",
                                args("zone", "DRIVER", "temperature", 24))), "explicit-driver");
            }
            return ModelTurn.directAnswer("用户越权,放弃");
        };

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("把主驾温度调到24度", Actor.PASSENGER)
                        .sessionId("pax-explicit-driver-e2e").build());

        // 两次都拒绝 → FAILED,从未实际调温
        assertEquals(TaskState.FAILED, outcome.getFinalState());
        // 第 1 轮:PARAMETER 拒绝(模型 passenger 与用户 driver 不一致)
        ToolObservation firstObs = outcome.getTrajectory().getIterations().get(0)
                .getObservations().get(0);
        assertFalse("first attempt must be PARAMETER (not capability-blocked)",
                firstObs.isCapabilityBlocked());
        // 第 2 轮:CAPABILITY 拒绝(模型对齐 driver 但 passenger 写 driver 越权)
        ToolObservation secondObs = outcome.getTrajectory().getIterations().get(1)
                .getObservations().get(0);
        assertTrue("retry with explicit driver zone must be capability-blocked",
                secondObs.isCapabilityBlocked());
    }

    /**
     * 漏场景 2:主驾明确要求"副驾调温",模型错误返回 zone=driver。
     * 旧 Policy 只检查 occupantZone=PASSENGER,主驾请求不会被显式约束保护。
     * 必须 PARAMETER 拒绝,让模型保持 zone=passenger 重试,通过。
     */
    @Test
    public void driverExplicitPassengerRejectsModelReturnedDriverThenAllowsRetry() {
        java.util.concurrent.atomic.AtomicInteger attempt = new java.util.concurrent.atomic.AtomicInteger();
        ModelGateway gateway = req -> {
            int n = attempt.incrementAndGet();
            if (n == 1) {
                return ModelTurn.ofToolCalls(Collections.singletonList(
                        new ToolCall("vehicle.climate.set_temperature",
                                args("zone", "DRIVER", "temperature", 24))), "wrong-zone");
            }
            if (n == 2) {
                return ModelTurn.ofToolCalls(Collections.singletonList(
                        new ToolCall("vehicle.climate.set_temperature",
                                args("zone", "passenger", "temperature", 24))), "correct-zone");
            }
            return ModelTurn.directAnswer("done");
        };

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("把副驾温度调到24度", Actor.DRIVER)
                        .sessionId("drv-explicit-passenger").build());

        // 主驾明确 zone=PASSENGER,模型对齐后通过(driver 写 passenger 合法)
        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(2, outcome.getResults().size());
        assertEquals(ToolResult.Status.POLICY_REJECTED, outcome.getResults().get(0).getStatus());
        assertFalse(outcome.getResults().get(0).isVerified());
        assertTrue(outcome.getResults().get(1).isSuccess());
    }

    /**
     * 模型固执返回用户没要求的区域时,每次都必须被 PARAMETER 拒绝,
     * 不能"绕过 1 次后放行"。预算耗尽前一直拒绝,直到 Loop 终止。
     */
    @Test
    public void modelStubbornlyReturnsWrongZoneIsRejectedEveryIteration() {
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(Collections.singletonList(
                new ToolCall("vehicle.climate.set_temperature",
                        args("zone", "passenger", "temperature", 24))), "stubborn-passenger");

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("把主驾温度调到24度", Actor.PASSENGER)
                        .sessionId("pax-stubborn").build());

        // 不允许 SUCCEEDED——模型从未对齐用户 zone=driver
        assertNotEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        for (AgentIteration iter : outcome.getTrajectory().getIterations()) {
            for (ToolObservation obs : iter.getObservations()) {
                assertFalse("every retry must stay PARAMETER (not escalate to capability)",
                        obs.isCapabilityBlocked());
            }
        }
    }

    /**
     * 回归:AgentOutcome.getInternalResults() 返回真实 ToolResult,
     * 而 getResults() 派生自 Audit Trajectory(memory preference value 是 <memory>)。
     * 业务调用方需要真实值时必须用 getInternalResults()。
     */
    @Test
    public void internalResultsHoldRealMemoryValueWhileAuditResultsRedacted() {
        AgentOutcome saved = engine.execute(new AgentRequest("记住我喜欢24度", Actor.DRIVER));

        // 内部可信域:真实值
        assertEquals("24", saved.getInternalResults().get(0).getObservedState().get("preferred_temperature"));
        assertTrue("internal message must contain real value",
                saved.getInternalResults().get(0).getMessage().contains("24℃"));
        // Audit 视图(派生自 Trajectory):占位符
        assertEquals(AuditRedactor.MEMORY_MESSAGE_PLACEHOLDER,
                saved.getResults().get(0).getMessage());
    }

    /**
     * 回归:Audit Trajectory 中 AssistantMessage.toolCalls 的 arguments
     * 必须也过 AuditRedactor。旧实现只 redact content,ToolCall 是原始的——
     * 导致同一 Trajectory 同时存在 ToolCallSnapshot(已脱敏)与 AssistantMessage.toolCalls
     * (未脱敏)两份参数。导航地址 / Memory Value / 联系人 等可能从后者泄漏。
     */
    @Test
    public void auditAssistantToolCallsArgumentsAreRedacted() {
        ToolCall callWithSecret = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24, "api_key", "sk-abcd1234abcd1234"));
        ModelGateway gateway = oneShot(callWithSecret, "has-secret");

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(new AgentRequest("调温(含密钥)", Actor.DRIVER));

        AgentMessage auditAssistant = outcome.getTrajectory().getIterations().get(0)
                .getAssistantMessage();
        assertNotNull(auditAssistant);
        assertEquals(1, auditAssistant.getToolCalls().size());
        Object redactedKey = auditAssistant.getToolCalls().get(0).getArguments().get("api_key");
        assertEquals("Audit Assistant ToolCall arguments must be masked",
                "***", redactedKey);
    }

    /**
     * 回归:模型输出因 max_tokens 截断(finish_reason=length)时,
     * AgentEngine 必须走 LENGTH_EXCEEDED 异常终止,finalState 不能 SUCCEEDED。
     * 旧实现一律 directAnswer(STOP)→ NO_TOOL_CALL → 可能 SUCCEEDED。
     */
    @Test
    public void lengthFinishReasonExitsLoopAsLengthExceededNotSucceeded() {
        ModelGateway gateway = req -> new ModelTurn(
                AgentMessage.assistant("答复被截断", Collections.emptyList()),
                FinishReason.LENGTH, 100);

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(new AgentRequest("随便问", Actor.DRIVER));

        assertNotEquals("LENGTH must not be SUCCEEDED", TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(StopReason.LENGTH_EXCEEDED, outcome.getTrajectory().getStopReason());
    }

    /**
     * 回归:Provider 协议不一致(finish_reason 缺失/未知,或 STOP 但 content 空)
     * 时,AgentEngine 必须走 PROTOCOL_ERROR 异常终止,不能被错判为 NO_TOOL_CALL → SUCCEEDED。
     * 旧实现把 NONE 一律当作"模型选择直接答复" → SUCCEEDED,把协议错误掩盖成正常完成。
     */
    @Test
    public void noneFinishReasonExitsLoopAsProtocolErrorNotSucceeded() {
        ModelGateway gateway = req -> new ModelTurn(
                AgentMessage.assistant("", Collections.emptyList()),
                FinishReason.NONE, 0);

        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(new AgentRequest("随便问", Actor.DRIVER));

        assertNotEquals("NONE must not be SUCCEEDED", TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(StopReason.PROTOCOL_ERROR, outcome.getTrajectory().getStopReason());
        assertEquals(TaskState.FAILED, outcome.getFinalState());
    }

    /**
     * 回归:Trajectory 的 ToolCallSnapshot.arguments 必须过 AuditRedactor——
     * 导航 destination / memory save value 不能原文进 UI/Log。
     *
     * <p>升级:destination 现在走 schema-aware 脱敏,整个值替换为 {@code <destination>},
     * 不再是只 mask sk-xxx 的原值。这样无论用户原话是什么形式,destination 都不会泄漏。
     */
    @Test
    public void trajectorySnapshotRedactsToolArguments() {
        AgentOutcome outcome = engine.execute(new AgentRequest(
                "导航到 sk-abcd1234abcd1234 这个地址", Actor.DRIVER));

        AgentIteration iteration = outcome.getTrajectory().getIterations().get(0);
        if (!iteration.getToolCalls().isEmpty()) {
            AgentIteration.ToolCallSnapshot snapshot = iteration.getToolCalls().get(0);
            String argsRepr = snapshot.getArguments().toString();
            assertFalse("destination raw value must NOT appear, got: " + argsRepr,
                    argsRepr.contains("sk-abcd1234abcd1234"));
            assertTrue("destination must be replaced with <destination> placeholder, got: " + argsRepr,
                    argsRepr.contains("<destination>"));
        }
    }

    /**
     * 回归:memory.preference.save 的 value 必须按 schema 脱敏为 {@code <memory>}。
     * 旧实现只跑凭据正则,只 mask sk-/Bearer 等;memory value(24)、home 地址等业务敏感字段
     * 全部原样进 Audit Snapshot 与 Assistant Message。
     */
    @Test
    public void memoryPreferenceValueIsRedactedBySchema() {
        AgentOutcome outcome = engine.execute(new AgentRequest("记住我喜欢24度", Actor.DRIVER));

        for (AgentIteration iter : outcome.getTrajectory().getIterations()) {
            for (AgentIteration.ToolCallSnapshot snapshot : iter.getToolCalls()) {
                if ("memory.preference.save".equals(snapshot.getCapabilityName())) {
                    Object value = snapshot.getArguments().get("value");
                    assertEquals("memory preference value must be <memory> in audit snapshot",
                            "<memory>", value);
                }
            }
        }
    }

    /**
     * 异常终止状态(修复):MAX_ITERATIONS / MAX_TOOL_CALLS / BUDGET_EXHAUSTED / POLICY_HALT
     * 不能返回 SUCCEEDED——即使之前有成功 Tool。模型一直调成功查询 Tool 直到耗尽预算,
     * 最多 PARTIALLY_SUCCEEDED,不能声称成功。
     */
    @Test
    public void loopTerminatedByMaxIterationsDoesNotReturnSucceeded() {
        // 一直调成功的 knowledge.answer 直到 MAX_ITERATIONS
        ToolCall call = new ToolCall("knowledge.answer", args("question", "x"));
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(Collections.singletonList(call), "loop");
        AgentEngine loopEngine = new AgentEngine(gateway, modelCallExecutor,
                new PolicyEngine(registry), registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor,
                new AgentBudget(2, 8, 8_000L, 8_000, 32_000));

        AgentOutcome outcome = loopEngine.execute(new AgentRequest("loop", Actor.DRIVER));

        assertEquals(StopReason.MAX_ITERATIONS, outcome.getStopReason());
        assertTrue("must have successful tool calls",
                outcome.getTrajectory().countSuccessfulToolCalls() > 0);
        assertNotEquals("abnormal stop must NOT be SUCCEEDED",
                TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(TaskState.PARTIALLY_SUCCEEDED, outcome.getFinalState());
    }

    @Test
    public void loopTerminatedByMaxToolCallsDoesNotReturnSucceeded() {
        ToolCall a = new ToolCall("knowledge.answer", args("question", "a"));
        ToolCall b = new ToolCall("knowledge.answer", args("question", "b"));
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(java.util.Arrays.asList(a, b), "loop");
        AgentEngine loopEngine = new AgentEngine(gateway, modelCallExecutor,
                new PolicyEngine(registry), registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor,
                new AgentBudget(8, 1, 8_000L, 8_000, 32_000));

        AgentOutcome outcome = loopEngine.execute(new AgentRequest("loop", Actor.DRIVER));

        assertEquals(StopReason.MAX_TOOL_CALLS, outcome.getStopReason());
        assertNotEquals("abnormal stop must NOT be SUCCEEDED",
                TaskState.SUCCEEDED, outcome.getFinalState());
    }

    /**
     * 数据边界(修复后的正确方向):
     * - Conversation(喂模型):ModelSanitizer 保留 memory.preference.* 真实 value——
     *   模型需要这些语义才能完成"我喜欢多少度"之类的后续决策。
     * - Trajectory(UI/审计):AuditRedactor 字段级脱敏,memory value → <memory>。
     *   主驾副驾可能共用 Android User,UI 查看者未必是数据所有者。
     */
    @Test
    public void conversationKeepsSemanticValueForModelWhileTrajectoryIsRedacted() {
        final java.util.concurrent.atomic.AtomicReference<String> toolContentSeenByModel =
                new java.util.concurrent.atomic.AtomicReference<>("");

        ModelGateway gateway = req -> {
            boolean alreadySaved = hasToolResult(req.getConversation());
            if (!alreadySaved) {
                return ModelTurn.ofToolCalls(Collections.singletonList(
                        new ToolCall("memory.preference.get",
                                args("key", "preferred_temperature"))), "save");
            }
            for (AgentMessage msg : req.getConversation()) {
                if (msg.getRole() == AgentMessage.Role.TOOL) {
                    toolContentSeenByModel.set(msg.getContent());
                    break;
                }
            }
            return ModelTurn.directAnswer("done");
        };

        // 先保存一个偏好,然后再触发 get
        engine.execute(AgentRequest.builder("记住我喜欢24度", Actor.DRIVER)
                .sessionId("redact-check").build());
        AgentOutcome outcome = createEngine(gateway, provider)
                .execute(AgentRequest.builder("我喜欢多少度", Actor.DRIVER)
                        .sessionId("redact-check").build());

        // Conversation 里喂回 LLM 的 tool message 必须保留真实值——
        // 模型答"我喜欢多少度"需要看到 24
        String seen = toolContentSeenByModel.get();
        assertTrue("conversation must keep semantic value for model, got: " + seen,
                seen.contains("24"));
        assertFalse("conversation must NOT be replaced with placeholder, got: " + seen,
                seen.contains(AuditRedactor.MEMORY_MESSAGE_PLACEHOLDER));
        assertFalse("conversation must NOT contain <memory> placeholder, got: " + seen,
                seen.contains("<memory>"));

        // Trajectory(UI/审计)的 observation 必须是脱敏的——不能暴露原始偏好
        ToolObservation trajObs = outcome.getTrajectory().getIterations().get(0)
                .getObservations().get(0);
        assertEquals(AuditRedactor.MEMORY_MESSAGE_PLACEHOLDER,
                trajObs.getResult().getMessage());
        assertEquals("<memory>",
                trajObs.getResult().getObservedState().get("preferred_temperature"));
    }

    private AgentEngine createEngine(ModelGateway gateway, CapabilityProvider capabilityProvider) {
        return new AgentEngine(gateway, modelCallExecutor, new PolicyEngine(registry), registry,
                capabilityProvider, sessionManager, new DefaultContextUpdater(), sessionLockManager,
                toolExecutor);
    }

    /** 注入 SteerMailbox 的工厂,启用运行时追加指令路径。 */
    private AgentEngine createEngineWithSteer(ModelGateway gateway,
            CapabilityProvider capabilityProvider, SteerMailbox mailbox) {
        return new AgentEngine(gateway, modelCallExecutor, new PolicyEngine(registry), registry,
                capabilityProvider, sessionManager, new DefaultContextUpdater(), sessionLockManager,
                toolExecutor, new AgentBudget(), mailbox);
    }

    /**
     * 单射 ModelGateway:第一次 decide 返回固定 ToolCall,看到 Observation 后直接答复。
     * 与 DemoModelGateway 行为对齐,便于把旧版 Planner lambda 平移到 Agent Loop。
     */
    private static ModelGateway oneShot(ToolCall call, String name) {
        return req -> {
            if (hasToolResult(req.getConversation())) {
                return ModelTurn.directAnswer(name + ": 已执行");
            }
            return ModelTurn.ofToolCalls(Collections.singletonList(call), name);
        };
    }

    private static ModelGateway knowledgeGateway() {
        ToolCall call = new ToolCall("knowledge.answer", args("question", "test"));
        return oneShot(call, "knowledge");
    }

    /**
     * 测试 1:REPROMPT 在第 1 轮 tool_call 完成后注入,第 2 轮 LLM 应看到追加 user message。
     *
     * <p>场景:
     * <ol>
     *   <li>第 1 轮 gateway 看到 user "查电量",返回 vehicle.info.get_battery tool_call</li>
     *   <li>CapabilityProvider 执行 tool 后注入 REPROMPT "顺便也查胎压" 到 mailbox</li>
     *   <li>第 2 轮 iteration 开始 drainSteerBeforeLlm 把 REPROMPT 合并进 conversation</li>
     *   <li>第 2 轮 gateway 应看到 2 个 user message(原指令 + 追加)</li>
     * </ol>
     */
    @Test
    public void agentLoopDrainsRepromptSteerBeforeLlmCall() {
        SteerMailbox mailbox = new SteerMailbox();
        ToolCall batteryCall = new ToolCall("vehicle.info.get_battery", args());
        AtomicInteger userMessageSeen = new AtomicInteger(0);
        AtomicBoolean repromptSeenInTurn2 = new AtomicBoolean(false);
        ModelGateway gateway = req -> {
            long userCount = req.getConversation().stream()
                    .filter(m -> m.getRole() == AgentMessage.Role.USER).count();
            userMessageSeen.set((int) userCount);
            if (userCount >= 2) {
                repromptSeenInTurn2.set(req.getConversation().stream()
                        .anyMatch(m -> m.getRole() == AgentMessage.Role.USER
                                && "顺便也查胎压".equals(m.getContent())));
                return ModelTurn.directAnswer("好的,一起查");
            }
            return ModelTurn.ofToolCalls(Collections.singletonList(batteryCall), "battery");
        };
        CapabilityProvider steeringProvider = (req, call) -> {
            mailbox.offer(req.getSessionId(), Steer.reprompt("顺便也查胎压"));
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "ok", Collections.<String, Object>emptyMap(), true, 5);
        };

        AgentOutcome outcome = createEngineWithSteer(gateway, steeringProvider, mailbox)
                .execute(AgentRequest.builder("查电量", Actor.DRIVER)
                        .sessionId("steer-reprompt").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(StopReason.NO_TOOL_CALL, outcome.getStopReason());
        assertEquals("第 2 轮 LLM 应被调用且看到 2 个 user message",
                2, userMessageSeen.get());
        assertTrue("第 2 轮 LLM 应看到 REPROMPT 文本", repromptSeenInTurn2.get());
    }

    /**
     * 测试 2:FORCE_TOOL 跳过 LLM 直接执行指定 capability。
     *
     * <p>场景:第 1 轮 tool_call 完成后,用户强制下一轮调用 vehicle.info.get_tire_pressure。
     * gateway 不会在第 2 轮被调用——直接由 drainSteerBeforeLlm 注入 ToolCall。
     */
    @Test
    public void agentLoopDrainsForceToolSteerSkippingLlm() {
        SteerMailbox mailbox = new SteerMailbox();
        AtomicInteger gatewayCallCount = new AtomicInteger(0);
        ModelGateway gateway = req -> {
            gatewayCallCount.incrementAndGet();
            // 第 1 轮(无 tool_result)→ 返回 battery tool_call
            // 第 2 轮(已有 tool_result,FORCE_TOOL 已执行)→ directAnswer 结束
            if (hasToolResult(req.getConversation())) {
                return ModelTurn.directAnswer("done");
            }
            ToolCall battery = new ToolCall("vehicle.info.get_battery", args());
            return ModelTurn.ofToolCalls(Collections.singletonList(battery), "battery");
        };
        CapabilityProvider steeringProvider = (req, call) -> {
            if ("vehicle.info.get_battery".equals(call.getCapabilityName())) {
                mailbox.offer(req.getSessionId(),
                        Steer.forceTool("vehicle.info.get_tire_pressure", Collections.<String, Object>emptyMap()));
            }
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "ok", Collections.<String, Object>emptyMap(), true, 5);
        };

        AgentOutcome outcome = createEngineWithSteer(gateway, steeringProvider, mailbox)
                .execute(AgentRequest.builder("查电量", Actor.DRIVER)
                        .sessionId("steer-force").build());

        assertEquals("FORCE_TOOL 注入的 tool 应执行成功",
                TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals("第 1 轮 battery tool_call + 第 2 轮 FORCE_TOOL tire_pressure = 2 个 tool",
                2, outcome.getTrajectory().getTotalToolCalls());
        assertEquals("gateway 应被调 2 次:第 1 轮 battery + 第 3 轮 directAnswer;"
                + "第 2 轮 FORCE_TOOL 跳过 LLM",
                2, gatewayCallCount.get());
    }

    /**
     * 测试 3:DEFER 用户推迟,StopReason.DEFERRED + TaskState.DEFERRED。
     */
    @Test
    public void agentLoopDrainsDeferredSteerStoppingLoop() {
        SteerMailbox mailbox = new SteerMailbox();
        ToolCall batteryCall = new ToolCall("vehicle.info.get_battery", args());
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(
                Collections.singletonList(batteryCall), "battery");
        CapabilityProvider steeringProvider = (req, call) -> {
            mailbox.offer(req.getSessionId(), Steer.defer());
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "ok", Collections.<String, Object>emptyMap(), true, 5);
        };

        AgentOutcome outcome = createEngineWithSteer(gateway, steeringProvider, mailbox)
                .execute(AgentRequest.builder("查电量", Actor.DRIVER)
                        .sessionId("steer-defer").build());

        assertEquals("DEFER 应导致 DEFERRED 终态",
                StopReason.DEFERRED, outcome.getStopReason());
        assertEquals("DEFER 应映射到 TaskState.DEFERRED",
                TaskState.DEFERRED, outcome.getFinalState());
        assertEquals("第 1 轮 normal tool_call iteration + 第 2 轮 DEFER iteration = 2",
                2, outcome.getTrajectory().getIterations().size());
    }

    /**
     * 测试 4:不同 session 的 Steer 不串号。
     */
    @Test
    public void steerMailboxDrainsPerSession() {
        SteerMailbox mailbox = new SteerMailbox();
        mailbox.offer("session-A", Steer.reprompt("AAA"));
        mailbox.offer("session-B", Steer.reprompt("BBB"));
        mailbox.offer("session-A", Steer.reprompt("AAA2"));

        List<Steer> drainedA = mailbox.drain("session-A");
        List<Steer> drainedB = mailbox.drain("session-B");
        List<Steer> drainedEmpty = mailbox.drain("session-C");

        assertEquals("session-A 应有 2 个 Steer", 2, drainedA.size());
        assertEquals("session-B 应有 1 个 Steer", 1, drainedB.size());
        assertEquals("AAA", drainedA.get(0).getPayload());
        assertEquals("AAA2", drainedA.get(1).getPayload());
        assertEquals("BBB", drainedB.get(0).getPayload());
        assertTrue("未注册的 session 应返回空 list", drainedEmpty.isEmpty());
        assertEquals("drain 后队列应清空", 0, mailbox.pendingCount("session-A"));
    }

    private static boolean hasToolResult(List<AgentMessage> conversation) {
        for (AgentMessage message : conversation) {
            if (message.getRole() == AgentMessage.Role.TOOL) return true;
        }
        return false;
    }

    private static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }
}
