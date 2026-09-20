package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.conversation.ConversationDomain.PersistedMessageStatus;
import com.matrix.agent.task.steer.Steer;
import com.matrix.agent.conversation.ConversationStore.MessageRow;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.platform.KeyedSerialDispatcher;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.conversation.ConversationContextAssembler;
import com.matrix.agent.task.conversation.ConversationHistorySource;
import com.matrix.agent.task.conversation.ConversationTaskSubmitter;
import com.matrix.agent.intent.KeywordIntentClassifier;
import com.matrix.agent.intent.MemoryIntentDetector;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinator 全链路（JVM 直驱 keyed lane）：TEXT 三态收敛、幂等重放、执行异常、
 * EXECUTION_UNKNOWN 忠实投影与派发拒绝（设计文档 §4.3 / §11.1 Coordinator 行）。
 */
public final class ConversationCoordinatorTest {

    private static final String CONV = UUID.randomUUID().toString();
    private static final String OWNER = "demo-driver";

    /** 直驱执行器：lane 任务在调用线程同步执行（确定性 FIFO）。 */
    private static class DirectPool implements java.util.concurrent.ExecutorService {
        @Override public void execute(Runnable command) {
            command.run();
        }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout,
                java.util.concurrent.TimeUnit unit) { return false; }
        @Override public <T> java.util.concurrent.Future<T> submit(
                java.util.concurrent.Callable<T> task) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
            throw new UnsupportedOperationException();
        }
        @Override public java.util.concurrent.Future<?> submit(Runnable task) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> List<java.util.concurrent.Future<T>> invokeAll(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout, java.util.concurrent.TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> T invokeAny(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> T invokeAny(
                java.util.Collection<? extends java.util.concurrent.Callable<T>> tasks,
                long timeout, java.util.concurrent.TimeUnit unit) {
            throw new UnsupportedOperationException();
        }
    }

    private static AgentOutcome success(String text) {
        Trajectory trajectory = new Trajectory();
        trajectory.finish(StopReason.NO_TOOL_CALL, 1L, 0);
        return new AgentOutcome(UUID.randomUUID().toString(), TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 1L, List.of(), text);
    }

    private static ConversationCoordinator buildHarness(FakeConversationStore store,
            ConversationCoordinator.TaskExecutor executor) {
        ConversationHistorySource emptyHistory = new ConversationHistorySource() {
            @Override public List<HistoryEntry> latestCompleted(String conversationId,
                    int maxEntries) {
                return List.of();
            }
        };
        ConversationTaskSubmitter submitter = new ConversationTaskSubmitter(
                KeywordIntentClassifier.INSTANCE, MemoryIntentDetector.NOOP,
                new ConversationContextAssembler(new com.matrix.agent.task.AgentBudget(),
                        emptyHistory));
        return new ConversationCoordinator(store, submitter, executor,
                new KeyedSerialDispatcher("test", new DirectPool(), 16),
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                (sessionId, steer) -> true /* steer 落点桩：追加路径单独断言 */);
    }

    /** harness 重载：注入自定义 steer 落点。 */
    private static ConversationCoordinator buildHarness(FakeConversationStore store,
            ConversationCoordinator.TaskExecutor executor,
            ConversationCoordinator.SteerSink sink) {
        ConversationHistorySource emptyHistory = new ConversationHistorySource() {
            @Override public List<HistoryEntry> latestCompleted(String conversationId,
                    int maxEntries) {
                return List.of();
            }
        };
        ConversationTaskSubmitter submitter = new ConversationTaskSubmitter(
                KeywordIntentClassifier.INSTANCE, MemoryIntentDetector.NOOP,
                new ConversationContextAssembler(new com.matrix.agent.task.AgentBudget(),
                        emptyHistory));
        return new ConversationCoordinator(store, submitter, executor,
                new KeyedSerialDispatcher("test", new DirectPool(), 16),
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                sink);
    }

    /** 手动池：lane 任务入队不执行，测试按需驱动（steer 需要宿主运行窗口）。 */
    private static final class ManualPool extends DirectPool {
        final java.util.List<Runnable> queue = new java.util.ArrayList<>();
        @Override public void execute(Runnable command) {
            synchronized (queue) { queue.add(command); }
        }
        Runnable take() {
            synchronized (queue) { return queue.remove(0); }
        }
    }

    /** 记录式 sink：捕获投递与 steerId；accept 开关模拟宿主拒绝。 */
    private static final class RecordingSink implements ConversationCoordinator.SteerSink {
        final java.util.List<Steer> offered =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean accept = true;
        @Override public boolean offerSteer(String sessionId, Steer steer) {
            offered.add(steer);
            return accept;
        }
    }

    /** 可编程执行桩（行为 lambda + 执行计数）。 */
    private static final class RefExecutor
            implements ConversationCoordinator.TaskExecutor {
        final AtomicInteger executions = new AtomicInteger();
        final AtomicReference<java.util.function.BiFunction<
                ConversationTaskSubmitter.PreparedTask, CancellationToken, AgentOutcome>> behavior =
                new AtomicReference<>((task, token) -> success("默认完成"));

        @Override public AgentOutcome execute(ConversationTaskSubmitter.PreparedTask task,
                CancellationToken token) {
            executions.incrementAndGet();
            return behavior.get().apply(task, token);
        }
    }

    private static ConversationCoordinator.TextCommand command(String text, String operationId) {
        return new ConversationCoordinator.TextCommand(CONV, text, "zh-CN", operationId,
                Actor.DRIVER, ConversationIds.agentSessionId(CONV, "DRIVER", "DRIVER"),
                "demo-vehicle");
    }

    @Test public void textHappyPathConvergesToCompletedWithAssistantRow() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> success("已将音量调整为 35%。"));
        List<MessageRow> upserts = new ArrayList<>();
        ConversationCoordinator coordinator = buildHarness(store, runtime);
        coordinator.setListener(new ConversationCoordinator.Listener() {
            @Override public void onMessageUpsert(MessageRow row) {
                upserts.add(row);
            }
        });

        ConversationCoordinator.TextAccepted accepted =
                coordinator.submitText(command("把音量调到35%", UUID.randomUUID().toString()));

        assertFalse(accepted.replay());
        assertEquals(1, runtime.executions.get());
        MessageRow user = store.findMessage(accepted.userMessageId());
        assertEquals(PersistedMessageStatus.COMPLETED.wire(), user.statusWire());
        assertEquals(ConversationMessage.ROLE_USER, user.roleWire());
        assertEquals(ConversationMessage.CHANNEL_TEXT, user.channelWire());

        // assistant 行存在且文本来自投影
        MessageRow assistant = upserts.stream()
                .filter(row -> row.roleWire() == ConversationMessage.ROLE_ASSISTANT)
                .findFirst().orElse(null);
        assertNotNull("终态必须产生 assistant upsert", assistant);
        assertEquals("已将音量调整为 35%。", assistant.text());
    }

    @Test public void idempotentReplayDoesNotExecuteTwice() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        ConversationCoordinator coordinator = buildHarness(store, runtime);
        String operationId = UUID.randomUUID().toString();

        ConversationCoordinator.TextAccepted first =
                coordinator.submitText(command("同一句话", operationId));
        ConversationCoordinator.TextAccepted second =
                coordinator.submitText(command("同一句话", operationId));

        assertTrue(second.replay());
        assertEquals(first.userMessageId(), second.userMessageId());
        assertEquals(1, runtime.executions.get());
    }

    @Test public void executionUnknownMapsFaithfully() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> {
            Trajectory trajectory = new Trajectory();
            trajectory.finish(StopReason.EXECUTION_UNKNOWN, 1L, 1);
            return new AgentOutcome(task.runtimeRequestId(), TaskState.EXECUTION_UNKNOWN,
                    StopReason.EXECUTION_UNKNOWN, trajectory, 1L, List.of(), null);
        });
        ConversationCoordinator coordinator = buildHarness(store, runtime);

        ConversationCoordinator.TextAccepted accepted =
                coordinator.submitText(command("调高亮度", UUID.randomUUID().toString()));
        MessageRow user = store.findMessage(accepted.userMessageId());
        assertEquals(PersistedMessageStatus.EXECUTION_UNKNOWN.wire(), user.statusWire());
        // assistant 文本必须如实说“未知”，绝不渲染成取消/失败措辞
        List<MessageRow> latest = store.latestMessages(CONV, 10);
        boolean unknownExplained = latest.stream()
                .anyMatch(row -> row.roleWire() == ConversationMessage.ROLE_ASSISTANT
                        && row.text().contains("执行结果未知"));
        assertTrue(unknownExplained);
    }

    @Test public void runtimeExceptionConvergesToFailed() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> {
            throw new IllegalStateException("boom");
        });
        ConversationCoordinator coordinator = buildHarness(store, runtime);

        ConversationCoordinator.TextAccepted accepted =
                coordinator.submitText(command("任何指令", UUID.randomUUID().toString()));
        MessageRow user = store.findMessage(accepted.userMessageId());
        assertEquals(PersistedMessageStatus.FAILED.wire(), user.statusWire());
    }

    @Test public void cancelledOutcomeMapsToCancelledStatus() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> {
            Trajectory trajectory = new Trajectory();
            trajectory.finish(StopReason.CANCELLED, 1L, 0);
            return new AgentOutcome(task.runtimeRequestId(), TaskState.CANCELLED,
                    StopReason.CANCELLED, trajectory, 1L, List.of(), null);
        });
        ConversationCoordinator coordinator = buildHarness(store, runtime);

        ConversationCoordinator.TextAccepted accepted =
                coordinator.submitText(command("可取消任务", UUID.randomUUID().toString()));
        assertEquals(PersistedMessageStatus.CANCELLED.wire(),
                store.findMessage(accepted.userMessageId()).statusWire());
    }

    @Test public void terminalAfterClearIsDroppedNotResurrected() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        final boolean[] cleared = {false};
        runtime.behavior.set((task, token) -> {
            // 模拟执行期间用户清库 → 终态回写时线程已不存在
            if (!cleared[0]) {
                store.clearForUsers(List.of(OWNER));
                cleared[0] = true;
            }
            return success("结果");
        });
        ConversationCoordinator coordinator = buildHarness(store, runtime);
        coordinator.submitText(command("将被清理的指令", UUID.randomUUID().toString()));
        // 清库后：不回写、不复活线程（消息已随线程删除）
        assertNull(store.findConversation(CONV));
    }

    @Test public void appendRejectedWhenNoRunningTask() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        ConversationCoordinator coordinator = buildHarness(store, new RefExecutor());
        assertNull("无运行任务时 appendSteer 必须 null（→ INVALID_STATE）",
                coordinator.appendSteer(CONV, "补充说明", UUID.randomUUID().toString()));
    }

    // ---------------- steer 合约测试（评估 v1.0 §4.3 / 阶段 0 清单） ----------------

    /**
     * 合约 1：宿主运行中追加 → 附属行持久化（RUNNING+OFFERED）、sink 恰好一次且带
     * steerId、宿主终态后镜像收敛 COMPLETED；宿主完成前后种子装配都取不到未终态 steer。
     */
    @Test public void steerPersistsAuxiliaryRowOffersOnceAndConvergesWithHost()
            throws Exception {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        runtime.behavior.set((task, token) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return success("已按补充调整。");
        });
        RecordingSink sink = new RecordingSink();
        ConversationHistorySource emptyHistory = new ConversationHistorySource() {
            @Override public List<HistoryEntry> latestCompleted(String conversationId,
                    int maxEntries) {
                return List.of();
            }
        };
        ConversationTaskSubmitter submitter = new ConversationTaskSubmitter(
                KeywordIntentClassifier.INSTANCE, MemoryIntentDetector.NOOP,
                new ConversationContextAssembler(new com.matrix.agent.task.AgentBudget(),
                        emptyHistory));
        ManualPool lane = new ManualPool();
        ConversationCoordinator coordinator = new ConversationCoordinator(store, submitter,
                runtime, new KeyedSerialDispatcher("test", lane, 16),
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                sink);

        ConversationCoordinator.TextAccepted host = coordinator.submitText(
                command("把音量调到35%", UUID.randomUUID().toString()));
        Thread worker = new Thread(() -> {
            try {
                lane.take().run();
            } catch (RuntimeException e) {
                // 宿主异常也需释放测试
            }
        }, "host-task");
        worker.start();
        while (store.findRunningTaskId(CONV) == null) {
            Thread.sleep(10);
        }

        // 宿主运行窗口内追加
        ConversationCoordinator.SteerAccepted steer = coordinator.appendSteer(
                CONV, "顺便把亮度也调到45%", UUID.randomUUID().toString());
        org.junit.Assert.assertNotNull(steer);
        assertFalse(steer.replay());
        MessageRow row = store.findMessage(steer.steerMessageId());
        assertEquals("附属行挂宿主用户消息", host.userMessageId(),
                row.steerHostUserMessageId());
        assertEquals(MessageRow.INPUT_STEER_WIRE, row.inputKindWire());
        assertEquals("sink 恰好一次", 1, sink.offered.size());
        assertEquals("投递带 steerId（运行时去重键）", steer.steerMessageId(),
                sink.offered.get(0).getSteerId());
        assertEquals("确认式投递成功 → OFFERED",
                ConversationMessage.STEER_DELIVERY_OFFERED, row.steerDeliveryWire());
        assertEquals("无独立 task link", null, row.conversationTaskId());

        // 宿主完成 → 附属行同事务镜像收敛
        release.countDown();
        worker.join(5_000);
        assertEquals("镜像收敛宿主终态", PersistedMessageStatus.COMPLETED.wire(),
                store.findMessage(steer.steerMessageId()).statusWire());
    }

    /** 合约 2：Binder 重试（同 clientOperationId）命中幂等行，不重复投递引擎。 */
    @Test public void steerIdempotentReplayDoesNotReoffer() throws Exception {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return success("完成");
        });
        RecordingSink sink = new RecordingSink();
        ConversationHistorySource emptyHistory = new ConversationHistorySource() {
            @Override public List<HistoryEntry> latestCompleted(String conversationId,
                    int maxEntries) {
                return List.of();
            }
        };
        ConversationTaskSubmitter submitter = new ConversationTaskSubmitter(
                KeywordIntentClassifier.INSTANCE, MemoryIntentDetector.NOOP,
                new ConversationContextAssembler(new com.matrix.agent.task.AgentBudget(),
                        emptyHistory));
        ManualPool lane = new ManualPool();
        ConversationCoordinator coordinator = new ConversationCoordinator(store, submitter,
                runtime, new KeyedSerialDispatcher("test", lane, 16),
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                sink);
        coordinator.submitText(command("把音量调到35%", UUID.randomUUID().toString()));
        Thread worker = new Thread(() -> lane.take().run(), "host-task");
        worker.start();
        while (store.findRunningTaskId(CONV) == null) {
            Thread.sleep(10);
        }

        String operationId = UUID.randomUUID().toString();
        ConversationCoordinator.SteerAccepted first =
                coordinator.appendSteer(CONV, "补充", operationId);
        ConversationCoordinator.SteerAccepted retry =
                coordinator.appendSteer(CONV, "补充", operationId);
        assertTrue("重试命中既有行", retry.replay());
        assertEquals("返回同一持久化行", first.steerMessageId(), retry.steerMessageId());
        assertEquals("引擎只被投递一次", 1, sink.offered.size());

        release.countDown();
        worker.join(5_000);
    }

    /** 合约 3：宿主拒绝投递 → 附属行自收敛 FAILED；宿主终态不覆盖该事实。 */
    @Test public void steerRejectedDeliverySelfConvergesFailedAndSurvivesHostTerminal()
            throws Exception {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return success("完成");
        });
        RecordingSink sink = new RecordingSink();
        sink.accept = false;
        ConversationHistorySource emptyHistory = new ConversationHistorySource() {
            @Override public List<HistoryEntry> latestCompleted(String conversationId,
                    int maxEntries) {
                return List.of();
            }
        };
        ConversationTaskSubmitter submitter = new ConversationTaskSubmitter(
                KeywordIntentClassifier.INSTANCE, MemoryIntentDetector.NOOP,
                new ConversationContextAssembler(new com.matrix.agent.task.AgentBudget(),
                        emptyHistory));
        ManualPool lane = new ManualPool();
        ConversationCoordinator coordinator = new ConversationCoordinator(store, submitter,
                runtime, new KeyedSerialDispatcher("test", lane, 16),
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                sink);
        coordinator.submitText(command("把音量调到35%", UUID.randomUUID().toString()));
        Thread worker = new Thread(() -> lane.take().run(), "host-task");
        worker.start();
        while (store.findRunningTaskId(CONV) == null) {
            Thread.sleep(10);
        }

        ConversationCoordinator.SteerAccepted steer = coordinator.appendSteer(
                CONV, "补充", UUID.randomUUID().toString());
        MessageRow rejected = store.findMessage(steer.steerMessageId());
        assertEquals("投递拒绝 → 消息 FAILED", PersistedMessageStatus.FAILED.wire(),
                rejected.statusWire());
        assertEquals("投递态 FAILED", ConversationMessage.STEER_DELIVERY_FAILED,
                rejected.steerDeliveryWire());

        release.countDown();
        worker.join(5_000);
        MessageRow afterHost = store.findMessage(steer.steerMessageId());
        assertEquals("宿主终态不覆盖已自收敛的 FAILED",
                PersistedMessageStatus.FAILED.wire(), afterHost.statusWire());
    }

    /**
     * 合约 4（store 级）：投递前进程死亡（PENDING 未决）→ 恢复对账按宿主收敛，
     * 但投递态保持 PENDING——“未确认是否并入”，绝不谎称已并入。
     */
    @Test public void pendingSteerConvergesWithHostButKeepsPendingDelivery() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        // 纯 store 级 setup：宿主提交 + 出队 RUNNING（不经 coordinator，隔离线程模型）
        String hostMessageId = ConversationIds.newMessageId();
        store.submitUserMessage(new com.matrix.agent.conversation.ConversationStore
                .UserSubmission(CONV, hostMessageId, ConversationMessage.CHANNEL_TEXT,
                "把音量调到35%", "zh-CN", "task-1", "req-1", false, "host-key-1"));
        store.markRunning("task-1");

        // 投递前死亡：行落库即 PENDING，无人 offer
        String steerId = ConversationIds.newMessageId();
        store.appendSteerMessage(new com.matrix.agent.conversation.ConversationStore
                .SteerSubmission(CONV, steerId, ConversationMessage.CHANNEL_TEXT,
                "补充", null, hostMessageId, "steer-key-1"));
        MessageRow pending = store.findMessage(steerId);
        assertEquals(ConversationMessage.STEER_DELIVERY_PENDING,
                pending.steerDeliveryWire());

        // 宿主终态（writeTerminal 路径）→ 镜像收敛但投递态不动
        store.writeTerminal(new com.matrix.agent.conversation.ConversationStore
                .TerminalWrite("task-1",
                PersistedMessageStatus.COMPLETED.wire(), 0, null, null));
        MessageRow converged = store.findMessage(steerId);
        assertEquals("状态随宿主保守收敛", PersistedMessageStatus.COMPLETED.wire(),
                converged.statusWire());
        assertEquals("投递态保持 PENDING——展示层只能声称『未确认并入』",
                ConversationMessage.STEER_DELIVERY_PENDING, converged.steerDeliveryWire());
    }

    /** 合约 5：运行中的 steer 不进种子装配（无双拼）；终态后作为定稿输入可见。 */
    @Test public void runningSteerExcludedFromSeedUntilTerminal() throws Exception {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return success("完成");
        });
        RecordingSink sink = new RecordingSink();
        ConversationHistorySource emptyHistory = new ConversationHistorySource() {
            @Override public List<HistoryEntry> latestCompleted(String conversationId,
                    int maxEntries) {
                return List.of();
            }
        };
        ConversationTaskSubmitter submitter = new ConversationTaskSubmitter(
                KeywordIntentClassifier.INSTANCE, MemoryIntentDetector.NOOP,
                new ConversationContextAssembler(new com.matrix.agent.task.AgentBudget(),
                        emptyHistory));
        ManualPool lane = new ManualPool();
        ConversationCoordinator coordinator = new ConversationCoordinator(store, submitter,
                runtime, new KeyedSerialDispatcher("test", lane, 16),
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                sink);
        coordinator.submitText(command("第一轮", UUID.randomUUID().toString()));
        Thread worker = new Thread(() -> lane.take().run(), "host-task");
        worker.start();
        while (store.findRunningTaskId(CONV) == null) {
            Thread.sleep(10);
        }

        ConversationCoordinator.SteerAccepted steer = coordinator.appendSteer(
                CONV, "运行中补充", UUID.randomUUID().toString());
        for (MessageRow row : store.latestCompletedForSeed(CONV, 50)) {
            org.junit.Assert.assertNotEquals("RUNNING steer 不得进入种子装配",
                    steer.steerMessageId(), row.messageId());
        }

        release.countDown();
        worker.join(5_000);
        boolean visibleAfterTerminal = false;
        for (MessageRow row : store.latestCompletedForSeed(CONV, 50)) {
            if (row.messageId().equals(steer.steerMessageId())) {
                visibleAfterTerminal = true;
            }
        }
        assertTrue("终态后 steer 作为定稿用户输入参与后续装配", visibleAfterTerminal);
    }

    // ---------------- 阶段 1 契约：窗口分页 / 重命名（评估 v1.0 §4.1-4.2） ----------------

    /** 锚点存在→窗口返回升序内容与双侧标志；锚点不存在→空页 + anchorExists=false。 */
    @Test public void windowAroundReturnsAscendingWindowOrAnchorMissing() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> success("完成"));
        ConversationCoordinator coordinator = buildHarness(store, runtime);
        for (int i = 1; i <= 5; i++) {
            coordinator.submitText(command("指令" + i, UUID.randomUUID().toString()));
        }
        // Fake 里 assistant 行也占 sequence；以真实行集校验窗口语义
        java.util.List<Long> sequences = new java.util.ArrayList<>();
        for (MessageRow row : store.latestMessages(CONV, 100)) {
            sequences.add(row.sequenceNo());
        }
        java.util.Collections.sort(sequences);
        long anchor = sequences.get(sequences.size() / 2);

        ConversationStore.MessageWindow window =
                coordinator.windowAround(CONV, anchor, 4);
        assertTrue(window.anchorExists());
        assertTrue("窗口按 sequence 升序",
                java.util.Objects.equals(window.messagesAscending().stream()
                        .map(MessageRow::sequenceNo).toList(),
                        window.messagesAscending().stream()
                                .map(MessageRow::sequenceNo).sorted().toList()));
        assertTrue("锚点前后都有消息时双侧标志为真", window.hasBefore() && window.hasAfter());

        ConversationStore.MessageWindow missing =
                coordinator.windowAround(CONV, anchor + 10_000, 4);
        assertFalse("不可定位统一空页", missing.anchorExists());
        assertTrue(missing.messagesAscending().isEmpty());
    }

    /** windowAfter：严格大于 afterSequenceExclusive 的升序窗口；不存在会话统一空窗。 */
    @Test public void windowAfterReturnsAscendingTailOrEmptyForMissingConversation() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        RefExecutor runtime = new RefExecutor();
        runtime.behavior.set((task, token) -> success("完成"));
        ConversationCoordinator coordinator = buildHarness(store, runtime);
        coordinator.submitText(command("第一条", UUID.randomUUID().toString()));
        coordinator.submitText(command("第二条", UUID.randomUUID().toString()));

        long firstSeq = store.latestMessages(CONV, 100).stream()
                .mapToLong(MessageRow::sequenceNo).min().orElseThrow();
        ConversationStore.MessageWindow tail = coordinator.windowAfter(CONV, firstSeq, 50);
        assertTrue(tail.anchorExists());
        assertTrue("尾部窗口至少含后续行", tail.messagesAscending().size() >= 1);
        assertTrue("尾部之后无更新消息", !tail.hasAfter());

        ConversationStore.MessageWindow missing =
                coordinator.windowAfter(UUID.randomUUID().toString(), 0, 50);
        assertFalse("不存在的会话统一 anchorMissing", missing.anchorExists());
    }

    /** 重命名：origin 置 USER；不存在会话返回 false。 */
    @Test public void renameSetsUserOriginAndRejectsMissingConversation() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        ConversationCoordinator coordinator = buildHarness(store, new RefExecutor());
        assertTrue(coordinator.renameConversation(CONV, "我的空调对话"));
        ConversationStore.ConversationRow renamed = store.findConversation(CONV);
        assertEquals(com.matrix.agent.api.conversation.ConversationInfo.TITLE_ORIGIN_USER,
                renamed.titleOrigin());
        assertEquals("我的空调对话", renamed.title());
        assertFalse(coordinator.renameConversation(UUID.randomUUID().toString(), "不存在"));
        org.junit.Assert.assertThrows("空标题拒绝", IllegalArgumentException.class,
                () -> coordinator.renameConversation(CONV, "   "));
    }

    @Test public void validationRejectsBlankAndOverlongText() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONV, OWNER);
        ConversationCoordinator coordinator = buildHarness(store, new RefExecutor());
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> coordinator.submitText(command("   ", UUID.randomUUID().toString())));
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> coordinator.submitText(command("x".repeat(4097),
                        UUID.randomUUID().toString())));
    }
}
