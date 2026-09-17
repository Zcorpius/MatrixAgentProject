package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.AgentEngine;
import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.DefaultContextUpdater;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.task.ModelGateway;
import com.matrix.agent.task.ModelTurn;
import com.matrix.agent.task.SteerMailbox;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskScheduler;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.task.identity.KeywordIntentClassifier;
import com.matrix.agent.task.identity.MockVehicleStateSource;
import com.matrix.agent.task.identity.VehicleState;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.data.session.SessionLockManager;
import com.matrix.agent.data.session.SessionManager;
import com.matrix.agent.task.tool.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolCall;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.task.tool.ToolResult;
import com.matrix.agent.data.audit.AuditRecord;
import com.matrix.agent.data.audit.AuditRepository;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 Repository 外层超时/中断 + 写操作(intentReadOnly=false)
 * 不再"空轨迹 TIMED_OUT / CANCELLED"——outcome 必须包含 EXECUTION_UNKNOWN 信息
 * (finalState=EXECUTION_UNKNOWN 或 trajectory 含 EXECUTION_UNKNOWN tool observation)。
 *
 * <p>评审场景:
 * <ul>
 *   <li>① Repository future.get(timeout) 超时 + 写命令已进入 Tool dispatch
 *       → token.cancel + grace window → ToolExecutor 观察到取消 → 写操作 EXECUTION_UNKNOWN
 *       → Engine trajectory 含 EXECUTION_UNKNOWN observation(若 grace window 收敛)
 *       → Repository fallback EXECUTION_UNKNOWN(若 grace window 未收敛)</li>
 *   <li>② 外部 interrupt Repository.execute + 写命令 → 直接 fallback(caller 已中断):
 *       写操作 EXECUTION_UNKNOWN(命令可能已被 Engine 分发到 Provider)。</li>
 * </ul>
 *
 * <p>断言语义:"Outcome 与 Audit 至少包含 EXECUTION_UNKNOWN,
 * 不能是空轨迹超时"——既覆盖 Repository fallback 路径,也覆盖 Engine convergence 路径。
 *
 * <p>对比旧行为:Repository catch TimeoutException / InterruptedException
 * 直接构造 TIMED_OUT/CANCELLED + 空 trajectory,与 ToolExecutor 写操作 EXECUTION_UNKNOWN
 * 语义冲突——命令可能已下发,Runtime 不能撒谎说"未发生"。
 */
public final class AgentRuntimeRepositoryWriteDispatchSemanticsTest {
    /**
     * ① 写命令 + Repository deadline=50ms + Provider sleep 2000ms(uninterruptible)
     *    + Gateway 立即返回 tool_call。
     *
     * <p>时序:
     * <ul>
     *   <li>t=2ms: Engine iteration 1, gateway 返回 tool_call</li>
     *   <li>t=3ms: Engine 调 ToolExecutor(write_call)</li>
     *   <li>t=3ms: ToolExecutor 启动 Provider(sleep 2000ms,不响应 interrupt)</li>
     *   <li>t=50ms: Repository future.get(50ms) 超时 → catch TimeoutException</li>
     *   <li>t=50ms: token.cancel() + awaitGraceOrFallback(500ms)</li>
     *   <li>t=50-100ms: ToolExecutor 轮询 isCancelled=true → tryAbort + 返回 EXECUTION_UNKNOWN</li>
     *   <li>t=100ms: Engine iteration 1 结束(EXECUTION_UNKNOWN observation)</li>
     *   <li>t=100ms: Engine iter 2 → cancellationState.isTimedOut=true → break TIMEOUT</li>
     *   <li>t=100ms: Engine 返回 TIMED_OUT trajectory(含 EXECUTION_UNKNOWN observation)</li>
     *   <li>t=100ms: awaitGraceOrFallback 收敛成功,用 Engine outcome(TIMED_OUT + observation)</li>
     * </ul>
     *
     * <p>断言(灵活覆盖两条路径):
     * <ul>
     *   <li>outcome.finalState = EXECUTION_UNKNOWN(Repository fallback),<b>或</b></li>
     *   <li>outcome.trajectory 含 EXECUTION_UNKNOWN tool observation(Engine convergence)</li>
     *   <li>outcome.trajectory.iterations 非空(证明 Engine 已 dispatch tool,不是空轨迹)</li>
     *   <li>token.isCancelled() = true</li>
     *   <li>audit.trajectoryJson 必须含 "EXECUTION_UNKNOWN" 字符串(若 finalState 不是 EXECUTION_UNKNOWN)</li>
     * </ul>
     */
    @Test
    public void repositoryTimeoutWithDispatchedWriteContainsExecutionUnknown() throws Exception {
        String writeCommand = "把主驾温度调到 24 度";
        assertFalse("前置:写命令必须 intentReadOnly=false",
                KeywordIntentClassifier.INSTANCE.isReadOnly(writeCommand));

        CapturingAuditRepository audit = new CapturingAuditRepository();
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);

        CountDownLatch toolDispatched = new CountDownLatch(1);
        // 用 releaseProvider latch 替代原 2000ms sleep——Provider 阻塞由测试
        // 主线程显式控制(测试结束后 countDown 放行),消除"sleep 总时长 vs budget/grace 总时长"
        // 的相对时序依赖,只剩"budget/grace 触发顺序"需要测;后者是确定的同步语义。
        CountDownLatch releaseProvider = new CountDownLatch(1);
        // Gateway 立即返回 tool_call(write capability,不阻塞)
        ModelGateway gateway = req -> ModelTurn.ofToolCalls(
                Collections.singletonList(buildClimateToolCall()), "set-temperature");

        // 不可中断 Provider:set_temperature 时阻塞在 releaseProvider latch,模拟真实 IPC(不响应 Java interrupt)
        CapabilityProvider uninterruptibleProvider = (request, call) -> {
            if ("vehicle.climate.set_temperature".equals(call.getCapabilityName())) {
                toolDispatched.countDown();
                while (releaseProvider.getCount() > 0) {
                    try {
                        releaseProvider.await();
                    } catch (InterruptedException ie) {
                        // 不响应 interrupt——真实 Binder 命令不会因 Java 中断而取消
                    }
                }
                return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                        "vehicle state set", Collections.emptyMap(), true, 0L);
            }
            return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                    "noop", Collections.emptyMap(), true, 0L);
        };

        // budget=200ms 替代 50ms。原 50ms 让 ToolExecutor(48ms)与 Repository
        // future.get(50ms)几乎同时触发,谁先到取决于 CPU 调度——Engine 先超时返回时 Repository
        // future.get 立即拿到 outcome 不进 catch → token.cancel 不被调用 → 断言失败。
        // 200ms 给 CPU 调度充足 buffer(单线程 Engine 主路径 + worker Tool dispatch 通常 < 50ms),
        // Repository future.get 必先于 ToolExecutor 内 future.get(198ms)超时,时序确定。
        AgentBudget budget = new AgentBudget(20, 20, 200, 5_000, 5_000, 100);
        CancellationToken token = new CancellationToken();
        AgentRuntimeRepository repo = buildRepository(gateway, stateSource, budget, audit,
                uninterruptibleProvider);

        // 把 repo.execute 放 caller 线程,测试主线程可控制时序——
        // 等 Provider 真的进入阻塞后,给 Repository future.get(200ms) + grace(500ms) 充分时间
        // 完成 catch TimeoutException → token.cancel → awaitWriteDispatchConvergence 全路径,
        // 不再依赖"测试主线程 sleep 与 budget 同时开始计时"的脆弱时序。
        AtomicReference<AgentOutcome> holder = new AtomicReference<>();
        Thread caller = new Thread(() -> holder.set(repo.execute(writeCommand, Actor.DRIVER, token)),
                "repo-write-timeout-caller");
        caller.start();

        // 等 Provider 真的进入(确认命令已 dispatch + Provider 阻塞)
        assertTrue("Provider 必须进入(write tool 已 dispatch)",
                toolDispatched.await(2, TimeUnit.SECONDS));

        // 给 Repository future.get(200ms) 超时 + grace(500ms) 收敛 + Engine 收尾充分时间(总 ~800ms)
        Thread.sleep(900);

        caller.join(3_000);
        // 测试断言完成后才放行 Provider(模拟真实不可中断 IPC,Provider 在测试整个生命周期内一直阻塞)
        releaseProvider.countDown();

        AgentOutcome outcome = holder.get();
        assertNotNull("caller 必须返回 outcome", outcome);

        // 核心断言:outcome 必须包含 EXECUTION_UNKNOWN 信息(finalState 或 trajectory observation)
        boolean finalStateIsExecUnknown = outcome.getFinalState() == TaskState.EXECUTION_UNKNOWN;
        boolean trajectoryHasExecUnknown = trajectoryContainsExecutionUnknown(outcome.getTrajectory());
        assertTrue("写操作 + Repository 超时:outcome 必须含 EXECUTION_UNKNOWN 信息"
                + "(finalState=" + outcome.getFinalState()
                + ", trajectoryHasExecUnknown=" + trajectoryHasExecUnknown
                + ", iterations=" + outcome.getTrajectory().getIterations().size() + ")",
                finalStateIsExecUnknown || trajectoryHasExecUnknown);
        // 不能是空轨迹(证明 Engine 确实 dispatch 了 tool)
        assertFalse("outcome 不能是空轨迹(Engine 必须已 dispatch write tool)",
                outcome.getTrajectory().getIterations().isEmpty());
        // Provider 必须被调用(命令确实下发)
        assertTrue("Provider 必须被调用(write tool 已 dispatch)",
                toolDispatched.getCount() == 0);
        // token 必须 cancel(协作取消信号已下发)
        assertTrue("token 必须 cancel(Repository timeout 触发 token.cancel)",
                token.isCancelled());

        // 等 worker 完成(Engine 内部 audit 也走 worker 线程)→ Engine + Repository 双重 audit
        awaitAuditCount(audit, 1, 3_000L);

        AuditRecord repoAudit = audit.findLatestByRequest(outcome.getRequestId());
        assertNotNull("Repository audit 必须落库", repoAudit);
        // 若 finalState 不是 EXECUTION_UNKNOWN,trajectory JSON 必须含 EXECUTION_UNKNOWN 字符串
        if (!"EXECUTION_UNKNOWN".equals(repoAudit.getFinalState())) {
            assertTrue("audit.trajectoryJson 必须含 EXECUTION_UNKNOWN(证明命令已下发,非空轨迹超时)"
                    + "(finalState=" + repoAudit.getFinalState() + ")",
                    repoAudit.getTrajectoryJson().contains("EXECUTION_UNKNOWN"));
        }
        // outcome.stopReason 与 trajectory.stopReason 必须一致(round-trip 契约)
        assertEquals("outcome.stopReason 必须与 trajectory.stopReason 一致",
                outcome.getStopReason(), outcome.getTrajectory().getStopReason());
    }

    /**
     * ② 写命令 + 外部 interrupt Repository.execute 线程 → catch InterruptedException
     *    → 直接 fallback(caller 已中断,不能阻塞调 future.get(graceMillis)):
     *    写操作 EXECUTION_UNKNOWN。
     *
     * <p>本路径不依赖 Engine 是否真正 dispatch 了 tool——Repository 仅依据 intentReadOnly=false
     * 决定 fallback。语义对齐:"command 被分类为写,Engine 可能已 dispatch,Runtime 不能宣称 CANCELLED"。
     *
     * <p>断言:
     * <ul>
     *   <li>outcome.finalState = EXECUTION_UNKNOWN(不是 CANCELLED)</li>
     *   <li>outcome.stopReason = EXECUTION_UNKNOWN(不是 CANCELLED)</li>
     *   <li>trajectory.stopReason 与 outcome.stopReason 一致</li>
     *   <li>Audit 落库 EXECUTION_UNKNOWN</li>
     *   <li>token.isCancelled() = true</li>
     * </ul>
     */
    @Test
    public void repositoryInterruptWithWriteCommandReturnsExecutionUnknown() throws Exception {
        String writeCommand = "调一下主驾座椅加热";
        assertFalse("前置:写命令必须 intentReadOnly=false",
                KeywordIntentClassifier.INSTANCE.isReadOnly(writeCommand));

        CapturingAuditRepository audit = new CapturingAuditRepository();
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Engine 阻塞在 gateway(不会真正 dispatch tool,但 intentReadOnly=false 即触发写语义保护)
        ModelGateway blocking = req -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return ModelTurn.directAnswer("late-write-after-interrupt");
        };

        // deadline=5s 让 Repository timeout 不触发,只测 InterruptedException 路径
        AgentBudget budget = new AgentBudget(2, 2, 5_000, 1_000, 1_000, 4);
        CancellationToken token = new CancellationToken();
        // 用默认 MockCapabilityProvider 即可,interrupt 路径不依赖 tool dispatch
        AgentRuntimeRepository repo = buildRepository(blocking, stateSource, budget, audit,
                new MockCapabilityProvider(new InMemoryMemoryStore()));

        AtomicReference<AgentOutcome> holder = new AtomicReference<>();
        Thread caller = new Thread(() -> holder.set(repo.execute(writeCommand, Actor.DRIVER, token)),
                "repo-write-caller");
        caller.start();
        entered.await(2, TimeUnit.SECONDS);

        // 外部 interrupt caller 线程,触发 Repository catch InterruptedException
        caller.interrupt();
        caller.join(3_000);

        AgentOutcome outcome = holder.get();
        assertNotNull("caller 必须返回 outcome", outcome);
        // 核心断言:写操作 + interrupt → EXECUTION_UNKNOWN(不是 CANCELLED)
        assertEquals("写操作 + Repository InterruptedException → finalState 必须为 EXECUTION_UNKNOWN",
                TaskState.EXECUTION_UNKNOWN, outcome.getFinalState());
        assertEquals("写操作 + Repository InterruptedException → stopReason 必须为 EXECUTION_UNKNOWN",
                StopReason.EXECUTION_UNKNOWN, outcome.getStopReason());
        assertEquals("outcome.stopReason 必须与 trajectory.stopReason 一致",
                outcome.getStopReason(), outcome.getTrajectory().getStopReason());

        awaitAuditCount(audit, 1, 3_000L);
        release.countDown();

        AuditRecord repoAudit = audit.findByRequestAndFinalState(
                outcome.getRequestId(), "EXECUTION_UNKNOWN");
        assertNotNull("Repository 兜底 audit 必须落库(EXECUTION_UNKNOWN)", repoAudit);
        assertEquals("EXECUTION_UNKNOWN", repoAudit.getFinalState());
        assertEquals("AuditRecord.stopReason 必须为 EXECUTION_UNKNOWN",
                "EXECUTION_UNKNOWN", repoAudit.getStopReason());
        Trajectory decoded = com.matrix.agent.data.db.TrajectoryCodec.decodeTrajectory(
                repoAudit.getTrajectoryJson());
        assertEquals("round-trip 后 trajectory.stopReason 必须与 AuditRecord.stopReason 一致",
                repoAudit.getStopReason(), decoded.getStopReason().name());

        assertTrue("token 必须 cancel(interrupt 路径也必须下发协作取消信号)",
                token.isCancelled());
    }

    private static ToolCall buildClimateToolCall() {
        Map<String, Object> args = new HashMap<>();
        args.put("zone", "DRIVER");
        args.put("temperature", 24);
        return new ToolCall("vehicle.climate.set_temperature", args);
    }

    /** 遍历 trajectory.iterations[*].observations[*].result.status 查找 EXECUTION_UNKNOWN。 */
    private static boolean trajectoryContainsExecutionUnknown(Trajectory trajectory) {
        for (AgentIteration iter : trajectory.getIterations()) {
            for (ToolObservation obs : iter.getObservations()) {
                ToolResult result = obs.getResult();
                if (result != null && result.getStatus() == ToolResult.Status.EXECUTION_UNKNOWN) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 等待 CapturingAuditRepository 收到至少 expectedCount 次 persist。 */
    private static void awaitAuditCount(CapturingAuditRepository audit, int expectedCount,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (audit.persistCount() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("audit persist 次数应在 " + timeoutMs + "ms 内达到 " + expectedCount
                + "(实际 " + audit.persistCount() + ")", audit.persistCount() >= expectedCount);
    }

    /**
     * 构造测试 Repository。{@code engineProvider} 用于注入自定义 CapabilityProvider
     * (例如慢/不可中断 Provider);Repository 自己的 snapshot 用 MockCapabilityProvider。
     */
    private static AgentRuntimeRepository buildRepository(ModelGateway gateway,
            MockVehicleStateSource stateSource, AgentBudget budget,
            CapturingAuditRepository audit, CapabilityProvider engineProvider) {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        SteerMailbox mailbox = new SteerMailbox();
        MockCapabilityProvider snapshotProvider = new MockCapabilityProvider(new InMemoryMemoryStore());
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, engineProvider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget, mailbox,
                new AgentEngineConfiguration.Builder().auditSink(audit::persist).build());
        return new AgentRuntimeRepository(engineFactory, snapshotProvider, sessionManager,
                new InMemoryMemoryStore(), gateway, "test-gateway", budget, scheduler,
                stateSource, registry,
                com.matrix.agent.task.identity.KeywordIntentClassifier.INSTANCE, audit);
    }

    /**
     * Fake AuditRepository:记录所有 persist 调用,提供按 requestId + finalState 精确查询。
     *
     * <p>不实现 TrajectoryDao 的 REPLACE 语义——所有 persist 都追加到 list,允许 Engine 内部
     * audit + Repository 兜底 audit 短期并存,测试通过 finalState 精确选 Repository 版本。
     */
    private static final class CapturingAuditRepository implements AuditRepository {
        private final java.util.List<AuditRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void persist(AgentOutcome outcome,
                com.matrix.agent.task.identity.AgentRequest request) {
            records.add(new AuditRecord(
                    outcome.getRequestId(),
                    request.getSessionId(),
                    request.getActor() == null ? "" : request.getActor().name(),
                    request.getOccupantZone() == null ? "" : request.getOccupantZone().name(),
                    com.matrix.agent.task.identity.ActorUsers.userIdOf(request),
                    outcome.getTrajectory().getStartedAtMillis(),
                    outcome.getDurationMillis(),
                    outcome.getStopReason().name(),
                    outcome.getFinalState().name(),
                    outcome.getTrajectory().getIterations().size(),
                    outcome.getTrajectory().getTotalToolCalls(),
                    outcome.getTrajectory().countSuccessfulToolCalls(),
                    com.matrix.agent.data.db.TrajectoryCodec.encode(outcome)));
        }

        @Override
        public AuditRecord queryByRequest(String userId, String zone, String requestId) {
            // 测试 fake 不做 scope 过滤,直接按 requestId 找——真实 RoomAuditRepository
            // 走 SQL WHERE userId+zone 过滤,跨用户/zone 返回 null
            return findLatestByRequest(requestId);
        }

        @Override
        public List<AuditRecord> queryBySession(String userId, String zone,
                String sessionId, int limit) {
            return Collections.emptyList();
        }

        int persistCount() {
            return records.size();
        }

        AuditRecord findLatestByRequest(String requestId) {
            for (int i = records.size() - 1; i >= 0; i--) {
                if (records.get(i).getRequestId().equals(requestId)) return records.get(i);
            }
            return null;
        }

        AuditRecord findByRequestAndFinalState(String requestId, String finalState) {
            for (int i = records.size() - 1; i >= 0; i--) {
                AuditRecord r = records.get(i);
                if (r.getRequestId().equals(requestId) && r.getFinalState().equals(finalState)) {
                    return r;
                }
            }
            return null;
        }
    }
}
