package com.matrix.agent.task;
import com.matrix.agent.host.di.*;
import com.matrix.agent.task.steer.*;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.intent.KeywordIntentClassifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.AgentEngine;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.DefaultContextUpdater;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.task.steer.SteerMailbox;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.demo.MockVehicleStateSource;
import com.matrix.agent.vehicle.VehicleState;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.data.audit.AuditRecord;
import com.matrix.agent.data.audit.AuditRepository;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 AgentRuntimeRepository 层兜底 Audit 覆盖
 * <b>不进入 AgentEngine 5 个出口点</b> 的真实调度终态。
 *
 * <p>评审场景:
 * <ul>
 *   <li>① Repository future.get(timeout) 超时 → Repository 自己构造 TIMED_OUT outcome;</li>
 *   <li>② 外部 interrupt Repository.execute → Repository 自己构造 CANCELLED outcome;</li>
 *   <li>③ 主驾抢占副驾 → Scheduler 重映射副驾 outcome 为 PREEMPTED,Repository 兜底 audit。</li>
 * </ul>
 *
 * <p>Engine 内部 audit + Repository 兜底 audit 短期并存(CapturingAuditRepository 记录所有 persist
 * 调用,允许同 requestId 出现 2 条),最终生产路径由 TrajectoryDao `@Insert(REPLACE)` 让 Repository
 * 后写覆盖 Engine 版本。
 */
public final class AgentRuntimeRepositoryAuditCoverageTest {

    /**
     * ① Repository future.get(timeout) 超时 → catch TimeoutException → 构造 TIMED_OUT outcome
     *    → audit 必须落库。这条路径 Engine.execute 还在 worker 线程跑(gateway 阻塞中),
     *    Engine 内部 audit 可能后续才触发——但 Repository 兜底 audit 是核心保证。
     */
    @Test
    public void repositoryFutureTimeoutWritesAudit() throws Exception {
        CapturingAuditRepository audit = new CapturingAuditRepository();
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // gateway 阻塞模拟"模型迟迟不返回"
        ModelGateway blocking = req -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return ModelTurn.directAnswer("done-after-cancel");
        };

        AgentBudget budget = new AgentBudget(2, 2, 80, 1000, 1000, 4);
        CancellationToken token = new CancellationToken();
        AgentRuntimeRepository repo = buildRepository(blocking, stateSource, budget, audit);

        AgentOutcome outcome = repo.execute("阻塞查询", Actor.DRIVER, token);

        assertEquals(TaskState.TIMED_OUT, outcome.getFinalState());
        assertEquals(StopReason.TIMEOUT, outcome.getStopReason());
        // outcome.stopReason 必须与 trajectory.stopReason 一致,避免 audit 落库后
        // 结构化列与 trajectoryJson.trajectory.stopReason 矛盾(列表显示 TIMED_OUT 但回放解码不一致)。
        assertEquals("outcome.stopReason 必须与 trajectory.stopReason 一致",
                outcome.getStopReason(), outcome.getTrajectory().getStopReason());

        // 等 worker 完成(Engine 内部 audit 也走 worker 线程,需要时间)
        // Engine + Repository 双重 audit 并存设计——等 2 条都落库后再选择 Repository 版本
        awaitAuditCount(audit, 1, 3000L);
        release.countDown();

        // Repository 兜底 audit 必须落库——按 requestId + finalState=TIMED_OUT 精确选 Repository 版本
        // (findLatestByRequest 在双写竞态下可能拿到 Engine 后写的 CANCELLED)
        AuditRecord repoAudit = audit.findByRequestAndFinalState(
                outcome.getRequestId(), "TIMED_OUT");
        assertNotNull("Repository 兜底 audit 必须落库(TIMED_OUT)", repoAudit);
        assertEquals("Repository audit 必须记录 TIMED_OUT", "TIMED_OUT", repoAudit.getFinalState());
        assertEquals("AuditRecord.stopReason 必须为 TIMEOUT", "TIMEOUT", repoAudit.getStopReason());
        // round-trip 后 AuditRecord.stopReason 与 decodeTrajectory(...).getStopReason() 一致
        Trajectory decodedFromAudit = com.matrix.agent.task.persistence.TrajectoryCodec.decodeTrajectory(
                repoAudit.getTrajectoryJson());
        assertEquals("round-trip 后 trajectory.stopReason 必须与 AuditRecord.stopReason 一致",
                repoAudit.getStopReason(), decodedFromAudit.getStopReason().name());
        assertTrue("token 必须 cancel(评审 P1.3 已修)", token.isCancelled());
    }

    /**
     * ② 外部 interrupt Repository.execute 线程 → catch InterruptedException → 构造 CANCELLED
     *    outcome → audit 必须落库。模拟 UI 层主动取消场景。
     */
    @Test
    public void repositoryExternalInterruptWritesAudit() throws Exception {
        CapturingAuditRepository audit = new CapturingAuditRepository();
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ModelGateway blocking = req -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return ModelTurn.directAnswer("done-after-interrupt");
        };

        AgentBudget budget = new AgentBudget(2, 2, 5_000, 1000, 1000, 4);
        CancellationToken token = new CancellationToken();
        AgentRuntimeRepository repo = buildRepository(blocking, stateSource, budget, audit);

        AtomicReference<AgentOutcome> holder = new AtomicReference<>();
        Thread caller = new Thread(() -> holder.set(repo.execute("阻塞查询", Actor.DRIVER, token)),
                "repo-caller");
        caller.start();
        entered.await(2, TimeUnit.SECONDS);

        // 外部 interrupt caller 线程,触发 Repository catch InterruptedException
        caller.interrupt();
        caller.join(3_000);

        AgentOutcome outcome = holder.get();
        assertNotNull("caller 必须返回 outcome", outcome);
        assertEquals(TaskState.CANCELLED, outcome.getFinalState());
        assertEquals(StopReason.CANCELLED, outcome.getStopReason());
        // outcome.stopReason 必须与 trajectory.stopReason 一致
        assertEquals("outcome.stopReason 必须与 trajectory.stopReason 一致",
                outcome.getStopReason(), outcome.getTrajectory().getStopReason());

        awaitAuditCount(audit, 1, 3000L);
        release.countDown();

        AuditRecord repoAudit = audit.findByRequestAndFinalState(
                outcome.getRequestId(), "CANCELLED");
        assertNotNull("Repository 兜底 audit 必须落库(外部 interrupt 路径,CANCELLED)", repoAudit);
        assertEquals("CANCELLED", repoAudit.getFinalState());
        assertEquals("AuditRecord.stopReason 必须为 CANCELLED",
                "CANCELLED", repoAudit.getStopReason());
        // round-trip 后 AuditRecord.stopReason 与 decodeTrajectory(...).getStopReason() 一致
        Trajectory decodedFromAudit = com.matrix.agent.task.persistence.TrajectoryCodec.decodeTrajectory(
                repoAudit.getTrajectoryJson());
        assertEquals("round-trip 后 trajectory.stopReason 必须与 AuditRecord.stopReason 一致",
                repoAudit.getStopReason(), decodedFromAudit.getStopReason().name());
        assertTrue("token 必须 cancel(评审 P1.3 已修)", token.isCancelled());
    }

    /**
     * ③ 主驾抢占副驾 → Scheduler.tryPreemptPassengerReadOnly + remapIfPreempted 把副驾 outcome
     *    改写为 PREEMPTED → Repository 兜底 audit 必须记录副驾 PREEMPTED。
     *
     *    <p>这是车机 Runtime 的关键场景——主驾查询抢占副驾查询的 audit 终态正确反映抢占语义,
     *    不能因为 Repository 不 audit 导致副驾终态丢失。
     */
    @Test
    public void driverPreemptionWritesAuditForPassengerPreempted() throws Exception {
        CapturingAuditRepository audit = new CapturingAuditRepository();
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);

        CountDownLatch passengerEntered = new CountDownLatch(1);
        CountDownLatch passengerReleased = new CountDownLatch(1);
        // passenger gateway 阻塞,driver gateway 直接返回 directAnswer
        ModelGateway gateway = req -> {
            if (req.getAgentRequest().getOccupantZone() == VehicleZone.PASSENGER) {
                passengerEntered.countDown();
                try {
                    passengerReleased.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return ModelTurn.directAnswer("passenger-cancelled");
            }
            return ModelTurn.directAnswer("driver-done");
        };

        AgentBudget budget = new AgentBudget(2, 2, 5_000, 1000, 1000, 4);
        CancellationToken passengerToken = new CancellationToken();
        CancellationToken driverToken = new CancellationToken();
        AgentRuntimeRepository repo = buildRepository(gateway, stateSource, budget, audit);

        AtomicReference<AgentOutcome> passengerHolder = new AtomicReference<>();
        Thread passengerThread = new Thread(() ->
                passengerHolder.set(repo.execute("查胎压", Actor.PASSENGER, passengerToken)),
                "passenger-caller");
        passengerThread.start();
        passengerEntered.await(2, TimeUnit.SECONDS);

        // 主驾查询(可抢占副驾只读)
        AgentOutcome driverOutcome = repo.execute("查电量", Actor.DRIVER, driverToken);
        assertEquals(TaskState.SUCCEEDED, driverOutcome.getFinalState());

        // 等副驾 caller 返回(token 已被 Scheduler.cancel → Engine cancellationState → PREEMPTED outcome)
        passengerReleased.countDown();
        passengerThread.join(3_000);
        AgentOutcome passengerOutcome = passengerHolder.get();
        assertNotNull("副驾 caller 必须返回 outcome", passengerOutcome);
        assertEquals("副驾终态必须是 PREEMPTED",
                TaskState.PREEMPTED, passengerOutcome.getFinalState());
        assertEquals(StopReason.PREEMPTED, passengerOutcome.getStopReason());
        // 抢占路径核心断言——outcome.stopReason == trajectory.stopReason == PREEMPTED
        // 修复前 trajectory.stopReason 仍是 Engine 写的 CANCELLED,与外层 PREEMPTED 矛盾
        assertEquals("副驾 outcome.stopReason 必须与 trajectory.stopReason 一致(都为 PREEMPTED)",
                passengerOutcome.getStopReason(), passengerOutcome.getTrajectory().getStopReason());
        assertEquals("副驾 trajectory.stopReason 必须为 PREEMPTED(rewriteStopReason 已覆盖)",
                StopReason.PREEMPTED, passengerOutcome.getTrajectory().getStopReason());

        awaitAuditCount(audit, 2, 3000L);

        // Repository 兜底 audit 必须记录副驾 PREEMPTED + 主驾 SUCCEEDED
        // 按 requestId + finalState 精确选 Repository 版本(Engine 内部 audit 写的是 CANCELLED,
        // 经 Scheduler remap + Repository rewriteStopReason 后才变为 PREEMPTED)
        AuditRecord passengerAudit = audit.findByRequestAndFinalState(
                passengerOutcome.getRequestId(), "PREEMPTED");
        assertNotNull("副驾 PREEMPTED 必须落 audit", passengerAudit);
        assertEquals("PREEMPTED", passengerAudit.getFinalState());
        assertEquals("AuditRecord.stopReason 必须为 PREEMPTED",
                "PREEMPTED", passengerAudit.getStopReason());
        // round-trip 后 trajectoryJson.trajectory.stopReason 必须为 PREEMPTED,
        // 与 AuditRecord.stopReason(结构化列)一致——否则列表显示"被抢占"但回放解码为"已取消"
        Trajectory decodedPassenger = com.matrix.agent.task.persistence.TrajectoryCodec.decodeTrajectory(
                passengerAudit.getTrajectoryJson());
        assertEquals("副驾 round-trip 后 trajectory.stopReason 必须为 PREEMPTED",
                StopReason.PREEMPTED, decodedPassenger.getStopReason());
        assertEquals("副驾 AuditRecord.stopReason 与 trajectory.stopReason 必须一致",
                passengerAudit.getStopReason(), decodedPassenger.getStopReason().name());

        AuditRecord driverAudit = audit.findByRequestAndFinalState(
                driverOutcome.getRequestId(), "SUCCEEDED");
        assertNotNull("主驾 SUCCEEDED 必须落 audit", driverAudit);
        assertEquals("SUCCEEDED", driverAudit.getFinalState());
        Trajectory decodedDriver = com.matrix.agent.task.persistence.TrajectoryCodec.decodeTrajectory(
                driverAudit.getTrajectoryJson());
        assertEquals("主驾 round-trip 后 AuditRecord.stopReason 与 trajectory.stopReason 必须一致",
                driverAudit.getStopReason(), decodedDriver.getStopReason().name());
    }

    /** 等待 CapturingAuditRepository 收到至少 expectedCount 次 persist(超时抛 AssertionFailed)。 */
    private static void awaitAuditCount(CapturingAuditRepository audit, int expectedCount,
            long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (audit.persistCount() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("audit persist 次数应在 " + timeoutMs + "ms 内达到 " + expectedCount
                + "(实际 " + audit.persistCount() + ")", audit.persistCount() >= expectedCount);
    }

    private static AgentRuntimeRepository buildRepository(ModelGateway gateway,
            MockVehicleStateSource stateSource, AgentBudget budget,
            CapturingAuditRepository audit) {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        SteerMailbox mailbox = new SteerMailbox();
        MockCapabilityProvider provider = new MockCapabilityProvider(new InMemoryMemoryStore());
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        // Engine 与 Repository 共享同一 audit 实例(模拟 AppContainer 真实装配)
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget, mailbox,
                new AgentEngineConfiguration.Builder()
                        .auditSink(new com.matrix.agent.task.persistence.AuditRepositoryAuditSink(audit))
                        .build());
        return new AgentRuntimeRepository(engineFactory, sessionManager,
                new InMemoryMemoryStore(), gateway, "test-gateway", budget, scheduler,
                stateSource, registry,
                com.matrix.agent.intent.KeywordIntentClassifier.INSTANCE, audit);
    }

    /**
     * Fake AuditRepository:记录所有 persist 调用,提供按 requestId 查询。
     *
     * <p>不实现 TrajectoryDao 的 REPLACE 语义——所有 persist 都追加到 list,
     * 这样测试可以观察"Engine 内部 audit + Repository 兜底 audit 短期并存"的实际调用次数。
     */
    private static final class CapturingAuditRepository implements AuditRepository {
        private final List<AuditRecord> records = new ArrayList<>();

        @Override
        public synchronized void persist(com.matrix.agent.data.audit.AuditOutcomeEntry entry) {
            records.add(new AuditRecord(
                    entry.requestId, entry.sessionId, entry.actor, entry.zone, entry.userId,
                    entry.startedMs, entry.durationMs, entry.stopReason, entry.finalState,
                    entry.iterationCount, entry.totalToolCalls, entry.successToolCalls,
                    entry.trajectoryJson));
        }

        @Override
        public AuditRecord queryByRequest(String userId, String zone, String requestId) {
            return findLatestByRequest(requestId);
        }

        @Override
        public List<AuditRecord> queryBySession(String userId, String zone,
                String sessionId, int limit) {
            return Collections.emptyList();
        }

        synchronized int persistCount() {
            return records.size();
        }

        synchronized AuditRecord findLatestByRequest(String requestId) {
            for (int i = records.size() - 1; i >= 0; i--) {
                if (records.get(i).getRequestId().equals(requestId)) return records.get(i);
            }
            return null;
        }

        /**
         * Engine + Repository 双重 audit 时序竞态下,findLatestByRequest 可能
         * 拿到 Engine 后写版本(CANCELLED/SUCCEEDED)而非 Repository 兜底版本(TIMED_OUT/CANCELLED)。
         * 本方法按 requestId + finalState 精确选 Repository 版本(由测试调用的 outcome.finalState 决定)。
         */
        synchronized AuditRecord findByRequestAndFinalState(String requestId, String finalState) {
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
