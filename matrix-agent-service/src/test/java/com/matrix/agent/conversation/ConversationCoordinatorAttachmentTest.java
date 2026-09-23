package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.conversation.persistence.RoomConversationDraftStore;
import com.matrix.agent.data.conversation.ConversationAttachmentDao;
import com.matrix.agent.data.conversation.ConversationAttachmentEntity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 附件提交集成（I6 §3.2/§9.2）：验证在所有持久化之前拒绝、投影并入 AgentRequest
 * 文本但消息正文不污染、同事务冻结。
 */
public final class ConversationCoordinatorAttachmentTest {

    private static final String OWNER = "demo-driver";

    /** 捕获 Agent 收到的 text（验证投影注入）。 */
    private static final class CapturingExecutor
            implements ConversationCoordinator.TaskExecutor {
        volatile String lastAgentText;

        @Override public AgentOutcome execute(
                com.matrix.agent.task.conversation.ConversationTaskSubmitter.PreparedTask task,
                com.matrix.agent.identity.CancellationToken token) {
            lastAgentText = task.text();
            Trajectory trajectory = new Trajectory();
            trajectory.finish(StopReason.NO_TOOL_CALL, 1L, 0);
            return new AgentOutcome(UUID.randomUUID().toString(), TaskState.SUCCEEDED,
                    StopReason.NO_TOOL_CALL, trajectory, 1L, List.of(), "done");
        }
    }

    /** 附件 DAO 桩：同域内查询按 owner 校验。 */
    private static final class StubAttachmentDao
            implements ConversationAttachmentDao {
        final Map<String, ConversationAttachmentEntity> rows = new HashMap<>();

        @Override public ConversationAttachmentEntity getById(String attachmentId) {
            return rows.get(attachmentId);
        }

        @Override public ConversationAttachmentEntity getByOperation(String ownerUserId,
                String vehicleZone, String clientOperationId) {
            return null;
        }

        @Override public void upsert(ConversationAttachmentEntity entity) {
            rows.put(entity.attachmentId, entity);
        }

        @Override public List<ConversationAttachmentEntity> listDraft(String conversationId) {
            List<ConversationAttachmentEntity> result = new ArrayList<>();
            for (ConversationAttachmentEntity entity : rows.values()) {
                if (entity.conversationId.equals(conversationId)
                        && entity.linkedMessageId == null) result.add(entity);
            }
            return result;
        }

        @Override public List<ConversationAttachmentEntity> listByMessage(String messageId) {
            List<ConversationAttachmentEntity> result = new ArrayList<>();
            for (ConversationAttachmentEntity entity : rows.values()) {
                if (messageId.equals(entity.linkedMessageId)) result.add(entity);
            }
            return result;
        }

        @Override public int linkToMessage(String attachmentId, String messageId, int ordinal) {
            ConversationAttachmentEntity entity = rows.get(attachmentId);
            if (entity == null || entity.linkedMessageId != null) return 0;
            entity.linkedMessageId = messageId;
            return 1;
        }

        @Override public int deleteDraft(String ownerUserId, String vehicleZone,
                String attachmentId) {
            ConversationAttachmentEntity entity = rows.get(attachmentId);
            if (entity == null || entity.linkedMessageId != null
                    || !ownerUserId.equals(entity.ownerUserId)
                    || !vehicleZone.equals(entity.vehicleZone)) return 0;
            rows.remove(attachmentId);
            return 1;
        }

        @Override public void deleteByUsers(List<String> userIds) {
            rows.values().removeIf(e -> userIds.contains(e.ownerUserId));
        }

        @Override public List<ConversationAttachmentEntity> listExpiredDrafts(long t) {
            return List.of();
        }

        @Override public void deleteById(String attachmentId) {
            rows.remove(attachmentId);
        }

        void addReady(String attachmentId, String conversationId, String text,
                String owner, String zone) {
            ConversationAttachmentEntity entity = new ConversationAttachmentEntity();
            entity.attachmentId = attachmentId;
            entity.ownerUserId = owner;
            entity.vehicleZone = zone;
            entity.conversationId = conversationId;
            entity.state = ConversationAttachmentEntity.STATE_READY;
            entity.extractedText = text;
            entity.mimeType = "text/plain";
            entity.safeDisplayName = attachmentId + ".txt";
            entity.clientOperationId = "seed-" + attachmentId;
            rows.put(attachmentId, entity);
        }
    }

    private static ConversationCoordinator harness(FakeConversationStore store,
            CapturingExecutor executor, StubAttachmentDao attachmentDao) {
        ConversationHistorySource emptyHistory = new ConversationHistorySource() {
            @Override public List<HistoryEntry> latestCompleted(String conversationId, int max) {
                return List.of();
            }
        };
        ConversationTaskSubmitter submitter = new ConversationTaskSubmitter(
                KeywordIntentClassifier.INSTANCE, MemoryIntentDetector.NOOP,
                new ConversationContextAssembler(new com.matrix.agent.task.AgentBudget(),
                        emptyHistory));
        ConversationCoordinator coordinator = new ConversationCoordinator(store, submitter,
                executor, new KeyedSerialDispatcher("test",
                        new ConversationCoordinatorTest.DirectPool(), 16),
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                (sessionId, steer) -> true);
        coordinator.setAttachmentPort(new com.matrix.agent.attachment
                .ConversationAttachmentPort(attachmentDao,
                new com.matrix.agent.attachment.AttachmentContextProjector(2000)));
        return coordinator;
    }

    private static ConversationCoordinator.TextCommand command(String conversationId,
            String text, List<String> attachmentIds) {
        return new ConversationCoordinator.TextCommand(conversationId, text, "zh-CN",
                UUID.randomUUID().toString(), com.matrix.agent.identity.Actor.DRIVER,
                ConversationIds.agentSessionId(conversationId, "DRIVER", "DRIVER"),
                "demo-vehicle", null, null, null, null, attachmentIds);
    }

    @Test
    public void readyAttachmentProjectedIntoAgentTextButMessageTextUnchanged() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        StubAttachmentDao attachmentDao = new StubAttachmentDao();
        attachmentDao.addReady("att-1", conversationId, "MEMORY=stored doc", OWNER, "DRIVER");
        CapturingExecutor executor = new CapturingExecutor();
        ConversationCoordinator coordinator = harness(store, executor, attachmentDao);

        ConversationCoordinator.UnifiedTextOutcome outcome =
                coordinator.submitTextOrAppend(command(conversationId, "总结附件",
                        List.of("att-1")));

        assertEquals(ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED, outcome.outcome());
        // AgentRequest 文本含投影：模型看到附件内容。
        assertNotNull(executor.lastAgentText);
        assertTrue("Agent 文本必须含附件边界",
                executor.lastAgentText.contains("<user_provided_document>"));
        assertTrue("Agent 文本必须含附件正文",
                executor.lastAgentText.contains("MEMORY=stored doc"));
        assertTrue("Agent 文本必须含用户原文", executor.lastAgentText.contains("总结附件"));
        // 消息正文不被污染：conversation_message.text 只有用户输入。
        ConversationStore.MessageRow message = store.findMessage(outcome.userMessageId());
        assertEquals("消息正文保持用户原文", "总结附件", message.text());
        // UserSubmission 携带了附件 id（Room store 侧在受理事务内执行 linkToMessage）。
        List<String> submitted = store.lastSubmittedAttachmentIds;
        assertEquals("附件 id 必须随 UserSubmission 传递", 1, submitted.size());
        assertEquals("att-1", submitted.get(0));
    }

    @Test
    public void nonexistentAttachmentRejectedBeforeAnyPersistence() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        StubAttachmentDao attachmentDao = new StubAttachmentDao();
        CapturingExecutor executor = new CapturingExecutor();
        ConversationCoordinator coordinator = harness(store, executor, attachmentDao);

        assertThrows("不存在附件必须 IAE（stub 映射 INVALID_ARGUMENT）",
                IllegalArgumentException.class,
                () -> coordinator.submitTextOrAppend(command(conversationId,
                        "试试", List.of("ghost"))));
        assertEquals("不得留下任何消息行", 0, store.messages.size());
        assertEquals("不得留下任何 link 行", 0, store.links.size());
    }

    @Test
    public void failedAttachmentRejected() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        StubAttachmentDao attachmentDao = new StubAttachmentDao();
        ConversationAttachmentEntity failed = new ConversationAttachmentEntity();
        failed.attachmentId = "att-bad";
        failed.ownerUserId = OWNER;
        failed.vehicleZone = "DRIVER";
        failed.conversationId = conversationId;
        failed.state = ConversationAttachmentEntity.STATE_FAILED;
        failed.mimeType = "application/octet-stream";
        failed.safeDisplayName = "blob.bin";
        failed.clientOperationId = "seed-bad";
        attachmentDao.rows.put("att-bad", failed);

        CapturingExecutor executor = new CapturingExecutor();
        ConversationCoordinator coordinator = harness(store, executor, attachmentDao);

        assertThrows("FAILED 附件不得提交", IllegalArgumentException.class,
                () -> coordinator.submitTextOrAppend(command(conversationId,
                        "试试", List.of("att-bad"))));
        assertEquals(0, store.messages.size());
    }

    @Test
    public void wrongOwnerAttachmentRejectedWithoutExistenceLeak() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        StubAttachmentDao attachmentDao = new StubAttachmentDao();
        attachmentDao.addReady("att-other", conversationId, "x", "demo-passenger", "DRIVER");
        CapturingExecutor executor = new CapturingExecutor();
        ConversationCoordinator coordinator = harness(store, executor, attachmentDao);

        assertThrows("越权附件必须拒绝（不泄漏存在性）", IllegalArgumentException.class,
                () -> coordinator.submitTextOrAppend(command(conversationId,
                        "试试", List.of("att-other"))));
    }

    @Test
    public void attachmentsWithoutPortConfiguredRejected() {
        FakeConversationStore store = new FakeConversationStore();
        String conversationId = store.seedConversation(
                UUID.randomUUID().toString(), OWNER).conversationId();
        ConversationHistorySource emptyHistory = new ConversationHistorySource() {
            @Override public List<HistoryEntry> latestCompleted(String c, int m) {
                return List.of();
            }
        };
        ConversationCoordinator coordinator = new ConversationCoordinator(store,
                new ConversationTaskSubmitter(KeywordIntentClassifier.INSTANCE,
                        MemoryIntentDetector.NOOP,
                        new ConversationContextAssembler(
                                new com.matrix.agent.task.AgentBudget(), emptyHistory)),
                (task, token) -> { throw new AssertionError("不该执行"); },
                new KeyedSerialDispatcher("test",
                        new ConversationCoordinatorTest.DirectPool(), 16),
                convId -> ConversationIds.agentSessionId(convId,
                        "DRIVER", "DRIVER"),
                (sessionId, steer) -> true);

        assertThrows("端口未装配 + 带附件 = 显式拒绝（不静默丢附件）",
                IllegalArgumentException.class,
                () -> coordinator.submitTextOrAppend(command(conversationId,
                        "试试", List.of("att-1"))));
    }
}
