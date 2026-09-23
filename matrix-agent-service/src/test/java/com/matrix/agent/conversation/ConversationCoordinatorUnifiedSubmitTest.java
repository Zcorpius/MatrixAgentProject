package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationRuntimeStage;
import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.conversation.ConversationStore.MessageRow;
import com.matrix.agent.platform.KeyedSerialDispatcher;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.conversation.ConversationContextAssembler;
import com.matrix.agent.task.conversation.ConversationHistorySource;
import com.matrix.agent.task.conversation.ConversationHistorySource.HistoryEntry;
import com.matrix.agent.task.conversation.ConversationTaskSubmitter;
import com.matrix.agent.intent.KeywordIntentClassifier;
import com.matrix.agent.intent.MemoryIntentDetector;
import com.matrix.agent.task.steer.Steer;

import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一提交（输入交互增强 §3.2）：Host 在会话门控内原子判定 steer / 主轮次。
 *
 * <p>锁定行为：空闲 → PRIMARY；宿主运行中且文本可追加 → STEER（回执宿主任务 id）；
 * 超过追加限额 → 落主轮次；clientOperationId 重放命中既有分支不重复执行；
 * 受理后发布 QUEUED、终态后阶段清理（I3 生命周期）。</p>
 */
public final class ConversationCoordinatorUnifiedSubmitTest {

    private static final String OWNER = "demo-driver";

    /** 手动池：宿主任务停在 lane 中不执行，制造“运行中”窗口。 */
    private static final class ManualPool
            extends ConversationCoordinatorTest.DirectPool {
        final List<Runnable> queue = new CopyOnWriteArrayList<>();

        @Override public void execute(Runnable command) {
            queue.add(command);
        }

        void drain() {
            while (!queue.isEmpty()) {
                queue.remove(0).run();
            }
        }
    }

    private static final class RecordingSink implements ConversationCoordinator.SteerSink {
        final List<Steer> offered = new CopyOnWriteArrayList<>();
        volatile boolean accept = true;

        @Override public boolean offerSteer(String sessionId, Steer steer) {
            if (accept) offered.add(steer);
            return accept;
        }
    }

    private static ConversationCoordinator harness(FakeConversationStore store,
            java.util.concurrent.ExecutorService lane, RecordingSink sink) {
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
        return new ConversationCoordinator(store, submitter,
                (task, token) -> success(), new KeyedSerialDispatcher("test", lane, 16),
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"), sink);
    }

    private static AgentOutcome success() {
        Trajectory trajectory = new Trajectory();
        trajectory.finish(StopReason.NO_TOOL_CALL, 1L, 0);
        return new AgentOutcome(UUID.randomUUID().toString(), TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 1L, List.of(), "done");
    }

    private static ConversationCoordinator.TextCommand command(String conversationId,
            String text, String operationId,
            ConversationCoordinator.AcceptedListener accepted) {
        return new ConversationCoordinator.TextCommand(conversationId, text, "zh-CN",
                operationId, com.matrix.agent.identity.Actor.DRIVER,
                ConversationIds.agentSessionId(conversationId, "DRIVER", "DRIVER"),
                "demo-vehicle", null, accepted, null);
    }

    @Test
    public void idleConversationSubmitsAsPrimaryRound() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ConversationCoordinator coordinator = harness(store,
                new ConversationCoordinatorTest.DirectPool(), new RecordingSink());

        ConversationCoordinator.UnifiedTextOutcome outcome =
                coordinator.submitTextOrAppend(command(conversationId, "打开主驾空调",
                        UUID.randomUUID().toString(), null));

        assertEquals(ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED, outcome.outcome());
        assertNotNull(outcome.conversationTaskId());
        assertFalseReplay(outcome);
        MessageRow row = store.findMessage(outcome.userMessageId());
        assertEquals(ConversationMessage.INPUT_PRIMARY, row.inputKindWire());
        assertNull(row.steerHostUserMessageId());
    }

    @Test
    public void runningHostReceivesSteerAndAcceptedListenerReceiptsHostTask() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ManualPool lane = new ManualPool();
        RecordingSink sink = new RecordingSink();
        ConversationCoordinator coordinator = harness(store, lane, sink);
        // 宿主任务受理后停在 lane（RUNNING 前，但 link 未终态 = 可接收）
        coordinator.submitText(command(conversationId, "打开主驾空调",
                UUID.randomUUID().toString(), null));

        AtomicReference<ConversationCoordinator.TextAccepted> receipt = new AtomicReference<>();
        ConversationCoordinator.UnifiedTextOutcome outcome =
                coordinator.submitTextOrAppend(command(conversationId, "温度调低一点",
                        UUID.randomUUID().toString(),
                        accepted -> receipt.set(accepted)));

        assertEquals(ConversationSubmission.OUTCOME_STEER_ACCEPTED, outcome.outcome());
        MessageRow steer = store.findMessage(outcome.userMessageId());
        assertEquals(ConversationMessage.INPUT_STEER, steer.inputKindWire());
        assertNotNull(steer.steerHostUserMessageId());
        // 回执必须指向宿主任务：VoiceBridge 的 token 映射对 steer/主轮次同时成立（§5.4）。
        assertNotNull(receipt.get());
        // Host may still be ACCEPTED/queued (the manual lane has not drained), so querying only
        // RUNNING is a race-prone way to identify it. The persisted steer host link is the
        // authoritative task association and must already be present in the receipt.
        assertEquals(store.findMessage(steer.steerHostUserMessageId()).conversationTaskId(),
                receipt.get().conversationTaskId());
        assertEquals(1, sink.offered.size());
        lane.drain(); // 收敛宿主，不留悬挂
    }

    /**
     * 评审 P1 回归：宿主终态的同一事务镜像收敛 steer 行后，必须为每行补发
     * upsert + status 事件——否则客户端缓存停留在 RUNNING（“正在并入”悬挂、
     * 取消入口残留），直到重进会话。事件即事实投影，不是可选的礼貌推送。
     */
    @Test
    public void hostTerminalConvergesSteerRowAndEmitsItsEvents() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ManualPool lane = new ManualPool();
        ConversationCoordinator coordinator = harness(store, lane, new RecordingSink());
        coordinator.submitText(command(conversationId, "打开主驾空调",
                UUID.randomUUID().toString(), null));
        ConversationCoordinator.UnifiedTextOutcome steerOutcome =
                coordinator.submitTextOrAppend(command(conversationId, "温度调低一点",
                        UUID.randomUUID().toString(), null));
        assertEquals(ConversationSubmission.OUTCOME_STEER_ACCEPTED, steerOutcome.outcome());
        String steerMessageId = steerOutcome.userMessageId();

        List<ConversationStore.MessageRow> steerUpserts = new CopyOnWriteArrayList<>();
        List<Integer> steerStatusEvents = new CopyOnWriteArrayList<>();
        coordinator.setListener(new ConversationCoordinator.Listener() {
            @Override public void onMessageUpsert(ConversationStore.MessageRow row) {
                if (steerMessageId.equals(row.messageId())) steerUpserts.add(row);
            }

            @Override public void onMessageStatusChanged(String convId, String messageId,
                    int statusWire, int failureCode) {
                if (steerMessageId.equals(messageId)) steerStatusEvents.add(statusWire);
            }
        });

        lane.drain(); // 宿主执行至终态 → writeTerminal 同事务收敛 steer

        // 存储事实：steer 行已随宿主镜像收敛，不再是 RUNNING。
        MessageRow convergedRow = store.findMessage(steerMessageId);
        assertTrue("steer 行必须已随宿主终态收敛",
                convergedRow.statusWire() != ConversationMessage.STATUS_ACCEPTED
                        && convergedRow.statusWire() != ConversationMessage.STATUS_RUNNING);
        // 事件投影：upsert 携带终态行、status 事件携带同一终态——客户端零改动即可
        // 摘除“正在并入”注记并收回取消入口。
        assertTrue("收敛 steer 必须收到至少一次 upsert", !steerUpserts.isEmpty());
        assertEquals("upsert 行必须是收敛后的终态",
                convergedRow.statusWire(), steerUpserts.get(steerUpserts.size() - 1).statusWire());
        assertTrue("收敛 steer 必须收到 status 事件", !steerStatusEvents.isEmpty());
        assertEquals(convergedRow.statusWire(), steerStatusEvents.get(0).intValue());
    }

    @Test
    public void textBeyondAppendLimitFallsBackToPrimaryEvenWhileHostRunning() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ManualPool lane = new ManualPool();
        ConversationCoordinator coordinator = harness(store, lane, new RecordingSink());
        coordinator.submitText(command(conversationId, "打开主驾空调",
                UUID.randomUUID().toString(), null));

        String longText = "长".repeat(ConversationCoordinator.APPEND_MAX_CHARS + 1);
        ConversationCoordinator.UnifiedTextOutcome outcome =
                coordinator.submitTextOrAppend(command(conversationId, longText,
                        UUID.randomUUID().toString(), null));

        assertEquals(ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED, outcome.outcome());
        assertEquals(ConversationMessage.INPUT_PRIMARY,
                store.findMessage(outcome.userMessageId()).inputKindWire());
        lane.drain();
    }

    @Test
    public void clientOperationReplayReturnsExistingBranchWithoutSecondExecution() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ConversationCoordinator coordinator = harness(store,
                new ConversationCoordinatorTest.DirectPool(), new RecordingSink());
        String operationId = UUID.randomUUID().toString();

        ConversationCoordinator.UnifiedTextOutcome first =
                coordinator.submitTextOrAppend(command(conversationId, "打开主驾空调",
                        operationId, null));
        ConversationCoordinator.UnifiedTextOutcome retry =
                coordinator.submitTextOrAppend(command(conversationId, "打开主驾空调",
                        operationId, null));

        assertEquals(ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED, first.outcome());
        assertEquals(ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED, retry.outcome());
        assertTrue("重放必须命中既有主轮次行", retry.replay());
        assertEquals(first.userMessageId(), retry.userMessageId());
    }

    @Test
    public void steerReplayWithSameOperationReturnsExistingSteerRow() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ManualPool lane = new ManualPool();
        RecordingSink sink = new RecordingSink();
        ConversationCoordinator coordinator = harness(store, lane, sink);
        coordinator.submitText(command(conversationId, "打开主驾空调",
                UUID.randomUUID().toString(), null));
        String operationId = UUID.randomUUID().toString();

        ConversationCoordinator.UnifiedTextOutcome first =
                coordinator.submitTextOrAppend(command(conversationId, "温度调低一点",
                        operationId, null));
        ConversationCoordinator.UnifiedTextOutcome retry =
                coordinator.submitTextOrAppend(command(conversationId, "温度调低一点",
                        operationId, null));

        assertEquals(ConversationSubmission.OUTCOME_STEER_ACCEPTED, first.outcome());
        assertEquals(ConversationSubmission.OUTCOME_STEER_ACCEPTED, retry.outcome());
        assertTrue(retry.replay());
        assertEquals(first.userMessageId(), retry.userMessageId());
        assertEquals("重放不得二次 offer", 1, sink.offered.size());
        lane.drain();
    }

    @Test
    public void rejectedSteerReplayStaysRejectedAndNeverConsumesTheRetryAsAccepted() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ManualPool lane = new ManualPool();
        RecordingSink sink = new RecordingSink();
        sink.accept = false;
        ConversationCoordinator coordinator = harness(store, lane, sink);
        coordinator.submitText(command(conversationId, "打开主驾空调",
                UUID.randomUUID().toString(), null));
        String operationId = UUID.randomUUID().toString();

        ConversationCoordinator.UnifiedTextOutcome first = coordinator.submitTextOrAppend(
                command(conversationId, "温度调低一点", operationId, null));
        ConversationCoordinator.UnifiedTextOutcome retry = coordinator.submitTextOrAppend(
                command(conversationId, "温度调低一点", operationId, null));

        assertEquals(ConversationSubmission.OUTCOME_STEER_DELIVERY_FAILED, first.outcome());
        assertEquals("幂等重放必须重述已知失败，而非伪装成已并入",
                ConversationSubmission.OUTCOME_STEER_DELIVERY_FAILED, retry.outcome());
        assertTrue(retry.replay());
        assertEquals(first.userMessageId(), retry.userMessageId());
        assertTrue("被拒绝的 steer 不应被记录为已 offer", sink.offered.isEmpty());
        assertEquals(ConversationMessage.STEER_DELIVERY_FAILED,
                store.findMessage(first.userMessageId()).steerDeliveryWire());
        lane.drain();
    }

    @Test
    public void stageTrackingPublishesQueuedOnAcceptAndClearsOnTerminal() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ConversationRuntimeStageRegistry registry = new ConversationRuntimeStageRegistry();
        ConversationTaskProgressBridge bridge = new ConversationTaskProgressBridge(registry,
                label -> label);
        // 直驱 lane：submitText 内部完成执行 + 终态。
        ConversationCoordinator coordinator = harness(store,
                new ConversationCoordinatorTest.DirectPool(), new RecordingSink());
        coordinator.setProgressTracking(registry, bridge);

        List<ConversationRuntimeStageRegistry.StageEvent> events = new CopyOnWriteArrayList<>();
        registry.setListener(new ConversationRuntimeStageRegistry.Listener() {
            @Override public void onRuntimeStage(
                    ConversationRuntimeStageRegistry.StageEvent event) {
                events.add(event);
            }
        });

        ConversationCoordinator.UnifiedTextOutcome outcome =
                coordinator.submitTextOrAppend(command(conversationId, "打开主驾空调",
                        UUID.randomUUID().toString(), null));

        // 直驱执行中 Engine 未挂 sink（harness 的 engine 是行为桩），因此只看到 QUEUED；
        // 终态后阶段必须清理——不留“正在执行”残影。
        assertNull("终态后注册表不得保留活跃阶段", registry.snapshotOf(conversationId));
        assertEquals(ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED, outcome.outcome());
        assertTrue("至少发布一次 QUEUED",
                events.stream().anyMatch(event ->
                        event.stage() == ConversationRuntimeStage.STAGE_QUEUED));
        assertEquals("终态清理同时解除 Engine 映射（bind/unbind 对称）", 0,
                bridge.activeBindings());
    }

    private static void assertFalseReplay(ConversationCoordinator.UnifiedTextOutcome outcome) {
        assertTrue(!outcome.replay());
    }
}
