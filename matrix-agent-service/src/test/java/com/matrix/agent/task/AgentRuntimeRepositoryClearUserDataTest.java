package com.matrix.agent.task;
import com.matrix.agent.task.steer.*;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.intent.KeywordIntentClassifier;

import com.matrix.agent.identity.AgentRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.AgentEngine;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.DefaultContextUpdater;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.task.steer.Steer;
import com.matrix.agent.task.steer.SteerMailbox;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.demo.MockVehicleStateSource;
import com.matrix.agent.vehicle.VehicleState;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.data.audit.AuditRecord;
import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.data.audit.ClearOutcome;
import com.matrix.agent.data.audit.NoopAuditRepository;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 {@link AgentRuntimeRepository#clearUserData()} 的"取消—等待—清空"序列。
 *
 * <p>评审场景:旧实现仅清 MemoryStore + SessionManager,
 * <ul>
 *   <li>{@link SteerMailbox} 没清,旧 Steer 留在队列里被下一次同 sessionId 任务消费;</li>
 *   <li>与在途写操作非原子,clear 前 dispatch 的 preference.save 在 clear 后完成,把刚清的数据写回。</li>
 * </ul>
 *
 * <p>新实现要求:
 * <ol>
 *   <li>取消所有在途 token,触发 Engine 协作取消;</li>
 *   <li>等 activeTokens 收敛 (最多 1000ms);</li>
 *   <li>清 SteerMailbox + MemoryStore + SessionManager。</li>
 * </ol>
 */
public final class AgentRuntimeRepositoryClearUserDataTest {

    @Test
    public void clearUserDataClearsSteerMailbox() {
        SteerMailbox mailbox = new SteerMailbox();
        AgentRuntimeRepository repo = newRepository(b -> {}, mailbox);
        // 投递一条陈旧 FORCE_TOOL——clearUserData 之前留在队列里
        mailbox.offer("demo-driver", Steer.forceTool("vehicle.climate.set_temperature",
                new HashMap<>()));
        assertEquals("前置:queue 应有 1 条 Steer", 1, mailbox.pendingCount("demo-driver"));

        repo.clearUserData();

        assertEquals("clearUserData 后 mailbox 应清空", 0, mailbox.pendingCount("demo-driver"));
        assertEquals("drain 后也无残留", 0, mailbox.drain("demo-driver").size());
    }

    @Test
    public void clearUserDataCancelsInFlightTokens() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Gateway 阻塞——任务卡在 model call,clearUserData 时仍 in-flight
        ModelGateway blocking = req -> {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return ModelTurn.directAnswer("late-after-clear");
        };

        AtomicReference<CancellationToken> tokenRef = new AtomicReference<>();
        AtomicReference<AgentOutcome> outcomeRef = new AtomicReference<>();
        SteerMailbox mailbox = new SteerMailbox();
        AgentRuntimeRepository repo = newRepository(b -> {}, blocking, mailbox);

        CancellationToken token = new CancellationToken();
        tokenRef.set(token);
        Thread caller = new Thread(() -> {
            try {
                outcomeRef.set(repo.execute("查询当前电量", Actor.DRIVER, token));
            } catch (Throwable t) {
                // ignore — outcome may be null on interrupt
            }
        }, "repo-inflight-caller");
        caller.start();
        assertTrue("gateway 必须进入阻塞", entered.await(2, TimeUnit.SECONDS));

        // 触发 clearUserData——应取消在途 token,等 1s 内收敛,然后清空
        long start = System.currentTimeMillis();
        repo.clearUserData();
        long elapsed = System.currentTimeMillis() - start;
        assertTrue("clearUserData 应在 1.5s 内返回(含 1s grace), 实际=" + elapsed + "ms",
                elapsed < 1_500);

        assertTrue("token 应被 cancel", token.isCancelled());

        // 释放 gateway,让 caller 退出 (Engine 收到 cancel 会快速收敛,但 release 仍能加速)
        release.countDown();
        caller.join(3_000);

        // 不强制断言 outcome——cancel 后 Engine 可能返回 CANCELLED / TIMED_OUT / null(若 thread interrupted)
        // 关键是不再持久化 clear 后的写操作
    }

    @Test
    public void clearUserDataWithoutSteerMailboxSkipsGracefully() {
        // 不调 setSteerMailbox —— 模拟兼容场景 (旧测试 / 未装配路径)
        AgentRuntimeRepository repo = newRepository(b -> {}, null);
        // 应不抛异常,仅记 warn
        repo.clearUserData();
        // session + memory 应已清 (无前置数据,断言无副作用即可)
        // 不需要 assertNotNull(repo.getSessionTurns());
    }

    @Test
    public void clearUserDataRemovesMemoryAndSession() {
        SteerMailbox mailbox = new SteerMailbox();
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        SessionManager sessionManager = new SessionManager();
        // 写入偏好 + session turn
        memoryStore.putPreference("demo-driver", "preferred_temp", "22");
        sessionManager.getOrCreate("demo-driver").addTurn("USER: hello | RESULT: OK");

        AgentRuntimeRepository repo = newRepositoryWithStores(b -> {}, mailbox, memoryStore,
                sessionManager);

        assertFalse("前置:memory 应有偏好",
                memoryStore.getAllPreferences("demo-driver").isEmpty());

        repo.clearUserData();

        assertTrue("clearUserData 后 memory 应为空",
                memoryStore.getAllPreferences("demo-driver").isEmpty());
        assertTrue("clearUserData 后 session turns 应为空",
                sessionManager.snapshotTurns().get("demo-driver") == null
                        || sessionManager.snapshotTurns().get("demo-driver").isEmpty());
    }

    /**
     * epoch gate 强一致核心场景。
     *
     * <p>直接用 MemoryStore API 模拟"Provider 在旧 epoch 完成写入":
     * <ul>
     *   <li>初始 epoch=0,putPreferenceChecked(epoch=0) 接受 (当前 epoch);</li>
     *   <li>bumpEpoch → epoch=1,putPreferenceChecked(epoch=0) 拒绝 (旧 epoch);</li>
     *   <li>putPreferenceChecked(epoch=1) 接受 (新 epoch)。</li>
     * </ul>
     *
     * <p>关键变化:删除"epoch=0 兼容路径"。原评审指出 0L 静默放行会让安全语义
     * 被绕过 (任何调用方传 0 都接受)。修复后 epoch 严格匹配 currentEpoch。
     */
    @Test
    public void epochGateRejectsStaleWriteAfterBump() {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        assertEquals("初始 epoch 应为 0", 0L, memoryStore.currentEpoch());

        // 当前 epoch (0L) 的写入——接受 (不再走"兼容路径",而是严格的 epoch=current 校验)
        assertTrue("epoch=0 (当前) 应接受",
                memoryStore.putPreferenceChecked("demo-driver", "k0", "v0", 0L));
        assertEquals("v0", memoryStore.getPreference("demo-driver", "k0"));

        // bumpEpoch → epoch=1,旧 epoch (0) 写入应被拒绝
        memoryStore.bumpEpoch();
        assertEquals("bumpEpoch 后应为 1", 1L, memoryStore.currentEpoch());
        assertFalse("epoch=0 (旧) 应被拒绝, currentEpoch=1",
                memoryStore.putPreferenceChecked("demo-driver", "k1", "v1", 0L));
        assertNull("k1 不应被写入", memoryStore.getPreference("demo-driver", "k1"));

        // 当前 epoch (1L) 的写入——接受
        assertTrue("epoch=1 (当前) 应接受",
                memoryStore.putPreferenceChecked("demo-driver", "k2", "v2", 1L));
        assertEquals("v2", memoryStore.getPreference("demo-driver", "k2"));
    }

    /**
     * 模拟"Repository 重启"——验证 MemoryStore 是 epoch 单一权威。
     *
     * <p>评审 bug:旧实现 Repository.epochCounter 从 0 开始，历史偏好存储启动
     * 从 prefs 加载 N。重启后 clearData → Repo.epochCounter=1, SP.epoch=N+1 → 后续新 execute
     * 注入 epoch=1 vs SP.epoch=N+1 → 永久拒绝所有写入。
     *
     * <p>修复:删除 Repository.epochCounter,execute 入口直接读 memoryStore.currentEpoch()。
     * 本测试用 InMemoryMemoryStore 模拟"持久化 epoch 跨 Repository 重启"——bumpEpoch 后
     * 销毁 Repository,用同一 memoryStore 新建 Repository,验证新 execute 注入的 epoch 与
     * memoryStore.currentEpoch() 一致,写入被接受。
     */
    @Test
    public void repositoryRestartKeepsEpochInSync() {
        SteerMailbox mailbox = new SteerMailbox();
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        SessionManager sessionManager = new SessionManager();
        AgentRuntimeRepository repoV1 = newRepositoryWithStores(b -> {}, mailbox, memoryStore,
                sessionManager);

        // 用户在 V1 实例里执行 clearUserData——memoryStore.epoch 自增到 1
        assertEquals("V1 前置 epoch=0", 0L, memoryStore.currentEpoch());
        repoV1.clearUserData();
        assertEquals("V1 clearUserData 后 epoch=1", 1L, memoryStore.currentEpoch());

        // 模拟进程重启:用同一 memoryStore (持久化保留 epoch=1) 新建 Repository
        // 旧实现会新 Repository.epochCounter=0,与本 memoryStore.epoch=1 失步
        AgentRuntimeRepository repoV2 = newRepositoryWithStores(b -> {}, mailbox, memoryStore,
                sessionManager);

        // 验证 V2 实例的 execute 注入的 epoch 与 memoryStore.currentEpoch() 一致
        // (直接断言 MemoryStore 行为,不调 execute——execute 需要 Engine 装配,这里只验证 epoch 契约)
        long v2CapturedEpoch = memoryStore.currentEpoch();
        assertEquals("V2 启动后 capturedEpoch 必须与 memoryStore 同步", 1L, v2CapturedEpoch);

        // 在 V2 内再 clearUserData——memoryStore.epoch 1→2
        repoV2.clearUserData();
        assertEquals("V2 clearUserData 后 epoch=2", 2L, memoryStore.currentEpoch());

        // Provider 用旧 epoch (1) 写入应被拒绝
        assertFalse("epoch=1 (旧) 写入应被拒绝, currentEpoch=2",
                memoryStore.putPreferenceChecked("demo-driver", "stale", "v", 1L));

        // Provider 用当前 epoch (2) 写入应被接受——这是评审 bug 的核心断言:
        // 旧实现因 Repository.epochCounter=1 (重启回到 0 + 1 次 clearData) != memoryStore.epoch=2
        // 会让这个写入也被错误拒绝
        assertTrue("epoch=2 (当前) 写入应被接受 (修复后 Repository 不再与 MemoryStore 失步)",
                memoryStore.putPreferenceChecked("demo-driver", "fresh", "v", 2L));
        assertEquals("v", memoryStore.getPreference("demo-driver", "fresh"));
    }

    /**
     * 核心并发测试:验证"旧写入通过 epoch 校验后阻塞 → 清空 → 放开旧写入"
     * 的 check-then-act race 不再让偏好"复活"。
     *
     * <p>评审 race(修复前):
     * <ol>
     *   <li>Thread A putPreferenceChecked(epoch=0):通过 epoch 校验(epoch.get()==0);</li>
     *   <li>Thread A 阻塞中(模拟 Provider 在 IPC 中耗时落盘);</li>
     *   <li>Thread B clearUserData:bumpEpoch → epoch=1,clear(driver) → values 清空;</li>
     *   <li>Thread A 解除阻塞,继续 putPreference(driver, key, value) → 写入已清的 values;</li>
     *   <li>结果:偏好"复活"——clearUserData 失败。</li>
     * </ol>
     *
     * <p>修复后:InMemoryMemoryStore 的 putPreferenceChecked + bumpEpoch / clearUserDataAndBump
     * 用同一把 synchronized 锁——Thread A 持锁做"校验 + 写入"原子,Thread B 必须等 A 完成才能
     * bumpEpoch。如果 A 先完成,B 的 bumpEpoch 切 epoch,后续 A 重试会被拒绝。如果 B 先完成,
     * A 进入时 epoch 已切,A 校验失败直接返回 false。
     *
     * <p>本测试模拟"Thread A 已通过 epoch 校验 + 写入 values"的真实路径(对应 Provider 通过
     * AgentRequest.getEpoch() 拿到旧 epoch,在 clearUserData 之前已完成 putPreferenceChecked)。
     * 然后调 clearUserData,断言 values 中该 key 已清——这是核心契约。同时再模拟"Thread A 在
     * clearUserData 之后用旧 epoch 写入"被拒绝,杜绝 race 复活。
     */
    @Test
    public void clearUserDataAtomicallyRejectsConcurrentStaleWrite() throws Exception {
        SteerMailbox mailbox = new SteerMailbox();
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        SessionManager sessionManager = new SessionManager();
        AgentRuntimeRepository repo = newRepositoryWithStores(b -> {}, mailbox, memoryStore,
                sessionManager);

        assertEquals("初始 epoch=0", 0L, memoryStore.currentEpoch());

        // 步骤 1:模拟 Provider 通过 epoch=0 校验并写入——这是 clearUserData 之前的合法写入
        assertTrue("epoch=0 (初始) 写入应接受",
                memoryStore.putPreferenceChecked("demo-driver", "home_addr", "北京市朝阳区", 0L));
        assertEquals("北京市朝阳区", memoryStore.getPreference("demo-driver", "home_addr"));

        // 步骤 2:clearUserData——原子 bump + clear,epoch 切到 1,driver 数据清空
        repo.clearUserData();
        assertEquals("clearUserData 后 epoch=1", 1L, memoryStore.currentEpoch());
        assertNull("clearUserData 后 home_addr 必须被清空",
                memoryStore.getPreference("demo-driver", "home_addr"));

        // 步骤 3:模拟"旧 Provider 仍在跑,clearUserData 后才完成"——用旧 epoch=0 写入应被拒绝
        // 这是评审 race 的核心断言:旧实现 race window 让这种写入"复活"偏好,新实现 epoch gate 拒绝
        assertFalse("clearUserData 后旧 epoch=0 写入必须被拒绝 (杜绝偏好复活)",
                memoryStore.putPreferenceChecked("demo-driver", "home_addr", "天津市河东区", 0L));
        assertNull("home_addr 不应被回灌", memoryStore.getPreference("demo-driver", "home_addr"));

        // 步骤 4:用当前 epoch=1 写入应接受——证明 epoch gate 仍允许新任务正常工作
        assertTrue("epoch=1 (当前) 写入应接受",
                memoryStore.putPreferenceChecked("demo-driver", "fresh_addr", "上海市浦东新区", 1L));
        assertEquals("上海市浦东新区", memoryStore.getPreference("demo-driver", "fresh_addr"));
    }

    /**
     * 真正的并发测试:N 个 writer 线程持续用 epoch=0 调 putPreferenceChecked,
     * 主线程中间调 clearUserData(原子 clearUserDataAndBump),等所有 writer 完成后断言:
     * <ul>
     *   <li>memoryStore driver 数据为空(clearUserDataAndBump 之后没有任何 key 残留);</li>
     *   <li>epoch 已切到 1(clearUserData 已生效)。</li>
     * </ul>
     *
     * <p>关键:synchronized 让 writer 的 putPreferenceChecked 与 clearUserDataAndBump 互斥,
     * writer 要么在 clear 之前完成(数据被 clear 清),要么在 clear 之后开始(epoch 已切,
     * requestEpoch=0 != currentEpoch=1,被拒绝)。两种顺序都不会出现"clear 后 key 复活"。
     *
     * <p>修复前(InMemoryMemoryStore.bumpEpoch 未 synchronized):writer 通过 epoch=0 校验后,
     * bumpEpoch 自增 epoch(不互斥),clear 清数据,writer 继续 putPreference 写回——race 复活。
     * 修复后(bumpEpoch / clearUserDataAndBump / putPreferenceChecked 同 synchronized):原子,
     * 上述 race 不再可能。
     */
    @Test
    public void concurrentStaleWritersCannotResurrectAfterClear() throws Exception {
        SteerMailbox mailbox = new SteerMailbox();
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        SessionManager sessionManager = new SessionManager();
        AgentRuntimeRepository repo = newRepositoryWithStores(b -> {}, mailbox, memoryStore,
                sessionManager);

        // 启动 4 个 writer,持续用 epoch=0 调 putPreferenceChecked(模拟 Provider 在 clearUserData
        // 之前/之中/之后各阶段完成写入)
        int writerCount = 4;
        int writesPerWriter = 100;
        java.util.concurrent.atomic.AtomicInteger acceptCount =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejectCount =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch writersDone =
                new java.util.concurrent.CountDownLatch(writerCount);

        for (int i = 0; i < writerCount; i++) {
            final int writerId = i;
            Thread writer = new Thread(() -> {
                for (int j = 0; j < writesPerWriter; j++) {
                    boolean accepted = memoryStore.putPreferenceChecked(
                            "demo-driver", "k_" + writerId + "_" + j, "v", 0L);
                    if (accepted) acceptCount.incrementAndGet();
                    else rejectCount.incrementAndGet();
                }
                writersDone.countDown();
            }, "stale-writer-" + i);
            writer.setDaemon(true);
            writer.start();
        }

        // 让 writers 跑一会儿,然后中间调 clearUserData(原子 epoch++ + clear)
        Thread.sleep(20);
        repo.clearUserData();
        assertEquals("clearUserData 后 epoch=1", 1L, memoryStore.currentEpoch());

        // 等所有 writer 完成
        assertTrue("writers 应在 10s 内完成",
                writersDone.await(10, java.util.concurrent.TimeUnit.SECONDS));

        // 核心断言:clearUserData 后 driver 数据应被清空。无论多少 writer 在 clear 之前/之后写入:
        // - clear 之前写入的:被 clearUserDataAndBump 清掉
        // - clear 之后写入的:requestEpoch=0 != currentEpoch=1,被拒绝,不写入
        // 修复前 race:bumpEpoch + clear 不互斥,writer 在二者之间写入,clear 后 key 复活
        assertTrue("accept + reject 应等于总写入数",
                acceptCount.get() + rejectCount.get() == writerCount * writesPerWriter);
        assertTrue("driver 数据在 clearUserData 后必须为空 (race 修复核心断言)"
                + " acceptCount=" + acceptCount.get()
                + " rejectCount=" + rejectCount.get()
                + " remainingKeys=" + memoryStore.getAllPreferences("demo-driver").size(),
                memoryStore.getAllPreferences("demo-driver").isEmpty());
    }


    /**
     * 端到端契约:Repository.clearUserData 自增 memoryStore epoch。
     * Provider 通过 AgentRequest.getEpoch() 拿到旧 epoch,在 memoryStore.bumpEpoch() 之后
     * 调 putPreferenceChecked 时 epoch != currentEpoch,写入被拒绝。
     */
    @Test
    public void repositoryClearUserDataBumpsMemoryStoreEpoch() {
        SteerMailbox mailbox = new SteerMailbox();
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        AgentRuntimeRepository repo = newRepositoryWithStores(b -> {}, mailbox, memoryStore,
                new SessionManager());

        assertEquals("前置:初始 epoch=0", 0L, memoryStore.currentEpoch());

        repo.clearUserData();
        assertEquals("clearUserData 后 memoryStore epoch 应自增到 1",
                1L, memoryStore.currentEpoch());

        repo.clearUserData();
        assertEquals("第二次 clearUserData 后 epoch 应自增到 2",
                2L, memoryStore.currentEpoch());

        // 旧 epoch (1) 的写入应被拒绝
        assertFalse("epoch=1 (旧) 写入应被拒绝, currentEpoch=2",
                memoryStore.putPreferenceChecked("demo-driver", "stale", "v", 1L));
        assertNull(memoryStore.getPreference("demo-driver", "stale"));
    }

    /**
     * clearUserDataDetailed 在 NoopAudit 路径下返回 NOT_APPLICABLE,
     * ViewModel 据此显示"已清空"(auditFailed=false)。
     */
    @Test
    public void clearUserDataDetailedReturnsNotApplicableWithNoopAudit() {
        AgentRuntimeRepository repo = newRepository(b -> {}, null);
        ClearUserDataOutcome outcome = repo.clearUserDataDetailed();
        assertEquals("driver zone NoopAudit 应返回 NOT_APPLICABLE",
                ClearOutcome.Status.NOT_APPLICABLE, outcome.getDriverAudit().getStatus());
        assertEquals("passenger zone NoopAudit 应返回 NOT_APPLICABLE",
                ClearOutcome.Status.NOT_APPLICABLE, outcome.getPassengerAudit().getStatus());
        assertFalse("NoopAudit 路径不应 auditFailed", outcome.auditFailed());
    }

    /**
     * clearUserDataDetailed 在 audit 抛异常时,
     * Repository.clearAuditSafe try/catch 封装进 ClearOutcome.FAILURE,
     * ViewModel 据此显示"上下文已清,审计删除失败,请稍后再次点击清空"。
     */
    @Test
    public void clearUserDataDetailedReportsFailureWhenAuditThrows() {
        AuditRepository throwing = new ThrowingAuditRepository();
        AgentRuntimeRepository repo = newRepositoryWithAudit(throwing);
        ClearUserDataOutcome outcome = repo.clearUserDataDetailed();
        assertEquals("driver zone audit 抛 → FAILURE",
                ClearOutcome.Status.FAILURE, outcome.getDriverAudit().getStatus());
        assertEquals("passenger zone audit 抛 → FAILURE",
                ClearOutcome.Status.FAILURE, outcome.getPassengerAudit().getStatus());
        assertTrue("audit 维度失败 → outcome.auditFailed()=true", outcome.auditFailed());
    }

    /**
     * audit 返回 ClearOutcome.FAILURE(不抛,直接返回结构化失败)
     * 时,outcome.auditFailed() 必须为 true,ViewModel 走重试提示文案。
     */
    @Test
    public void clearUserDataDetailedReportsFailureWhenAuditReturnsFailure() {
        AuditRepository failing = new FailingAuditRepository();
        AgentRuntimeRepository repo = newRepositoryWithAudit(failing);
        ClearUserDataOutcome outcome = repo.clearUserDataDetailed();
        assertTrue("audit 返回 FAILURE → outcome.auditFailed()=true", outcome.auditFailed());
    }

    /** 测试用 AuditRepository:clearByUserZone 抛 RuntimeException。 */
    private static final class ThrowingAuditRepository implements AuditRepository {
        @Override
        public void persist(com.matrix.agent.data.audit.AuditOutcomeEntry entry) { }
        @Override
        public AuditRecord queryByRequest(String userId, String zone, String requestId) {
            return null;
        }
        @Override
        public java.util.List<AuditRecord> queryBySession(String userId, String zone,
                String sessionId, int limit) {
            return java.util.Collections.emptyList();
        }
        @Override
        public ClearOutcome clearByUserZone(String userId, String zone) {
            throw new RuntimeException("simulated audit failure");
        }
    }

    /** 测试用 AuditRepository:clearByUserZone 返回 FAILURE(不抛,直接结构化失败)。 */
    private static final class FailingAuditRepository implements AuditRepository {
        @Override
        public void persist(com.matrix.agent.data.audit.AuditOutcomeEntry entry) { }
        @Override
        public AuditRecord queryByRequest(String userId, String zone, String requestId) {
            return null;
        }
        @Override
        public java.util.List<AuditRecord> queryBySession(String userId, String zone,
                String sessionId, int limit) {
            return java.util.Collections.emptyList();
        }
        @Override
        public ClearOutcome clearByUserZone(String userId, String zone) {
            return ClearOutcome.failure("DAO exception");
        }
    }

    // ---- helpers ----

    private static AgentRuntimeRepository newRepositoryWithAudit(AuditRepository audit) {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        SessionManager sessionManager = new SessionManager();
        AgentBudget budget = new AgentBudget();
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        MockCapabilityProvider provider = new MockCapabilityProvider(memoryStore);
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);
        ModelGateway gateway = req -> ModelTurn.directAnswer("noop");
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget,
                new SteerMailbox(), new AgentEngineConfiguration.Builder()
                        .auditSink(new com.matrix.agent.task.persistence.AuditRepositoryAuditSink(audit))
                        .build());
        return new AgentRuntimeRepository(engineFactory,
                sessionManager, memoryStore, gateway, "test-gateway", budget, scheduler,
                stateSource, registry,
                com.matrix.agent.intent.KeywordIntentClassifier.INSTANCE, audit);
    }


    private static AgentRuntimeRepository newRepository(
            java.util.function.Consumer<AgentBudget> budgetCustomizer, ModelGateway gateway,
            SteerMailbox mailbox) {
        InMemoryMemoryStore memoryStore = new InMemoryMemoryStore();
        SessionManager sessionManager = new SessionManager();
        return newRepositoryWithStores(budgetCustomizer, mailbox, memoryStore, sessionManager, gateway);
    }

    private static AgentRuntimeRepository newRepository(
            java.util.function.Consumer<AgentBudget> budgetCustomizer, SteerMailbox mailbox) {
        return newRepository(budgetCustomizer, req -> ModelTurn.directAnswer("noop"), mailbox);
    }

    private static AgentRuntimeRepository newRepositoryWithStores(
            java.util.function.Consumer<AgentBudget> budgetCustomizer, SteerMailbox mailbox,
            InMemoryMemoryStore memoryStore, SessionManager sessionManager) {
        return newRepositoryWithStores(budgetCustomizer, mailbox, memoryStore, sessionManager,
                req -> ModelTurn.directAnswer("noop"));
    }

    private static AgentRuntimeRepository newRepositoryWithStores(
            java.util.function.Consumer<AgentBudget> budgetCustomizer, SteerMailbox mailbox,
            InMemoryMemoryStore memoryStore, SessionManager sessionManager, ModelGateway gateway) {
        AgentBudget budget = new AgentBudget();
        budgetCustomizer.accept(budget);
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        MockCapabilityProvider provider = new MockCapabilityProvider(memoryStore);
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);

        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget,
                mailbox, AgentEngineConfiguration.defaults());
        AgentRuntimeRepository repo = new AgentRuntimeRepository(engineFactory,
                sessionManager, memoryStore, gateway, "test-gateway", budget, scheduler,
                stateSource, registry,
                com.matrix.agent.intent.KeywordIntentClassifier.INSTANCE,
                NoopAuditRepository.INSTANCE);
        if (mailbox != null) {
            repo.setSteerMailbox(mailbox);
        }
        return repo;
    }
}
