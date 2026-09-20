package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.conversation.ConversationDomain.PersistedMessageStatus;
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
    private static final class DirectPool implements java.util.concurrent.ExecutorService {
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
                (sessionId, steer) -> { /* steer 落点桩：追加路径单独断言 */ });
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
        assertFalse("无运行任务时 append 必须 false（→ INVALID_STATE）",
                coordinator.appendToRunningTask(CONV, "补充说明"));
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
