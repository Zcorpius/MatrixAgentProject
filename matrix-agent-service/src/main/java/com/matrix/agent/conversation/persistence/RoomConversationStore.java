package com.matrix.agent.conversation.persistence;

import android.util.Log;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.conversation.ConversationDomain.PersistedMessageStatus;
import com.matrix.agent.conversation.ConversationIds;
import com.matrix.agent.conversation.ConversationStore;
import com.matrix.agent.data.conversation.ConversationDao;
import com.matrix.agent.data.conversation.ConversationEntity;
import com.matrix.agent.data.conversation.ConversationMessageDao;
import com.matrix.agent.data.conversation.ConversationMessageEntity;
import com.matrix.agent.data.conversation.ConversationTaskLinkDao;
import com.matrix.agent.data.conversation.ConversationTaskLinkEntity;
import com.matrix.agent.data.db.MatrixDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@link ConversationStore} 的 Room 投影（设计文档 §4.2：conversation/persistence 是
 * conversation → data 的唯一投影位）。
 *
 * <p>事务纪律：submitUserMessage / writeTerminal / writeRecoveryOutcome 各为单个
 * {@code runInTransaction}——幂等判重、sequence 分配（MAX+1）、消息与 link 写入在
 * 同一事务内由 SQLite 写锁串行化；任何半写由 Room 回滚。clearForUsers 三表级联删除
 * 同事务执行。</p>
 */
public final class RoomConversationStore implements ConversationStore {

    private static final String TAG = "MatrixAgent";
    private static final int WIRE_SCHEMA_VERSION = 2;

    private final MatrixDatabase database;
    private final ConversationDao conversations;
    private final ConversationMessageDao messages;
    private final ConversationTaskLinkDao links;
    private final ConversationStoreTransactionRunner transaction;

    /** 生产注入 database::runInTransaction；JVM 测试注入 Runnable::run。 */
    public interface ConversationStoreTransactionRunner {
        void runInTransaction(Runnable body);
    }

    public RoomConversationStore(MatrixDatabase database,
            ConversationStoreTransactionRunner transaction) {
        this.database = database;
        this.conversations = database.conversationDao();
        this.messages = database.conversationMessageDao();
        this.links = database.conversationTaskLinkDao();
        this.transaction = transaction;
    }

    // ---- 线程 ----

    @Override
    public ConversationRow createConversation(NewConversation command) {
        ConversationEntity entity = new ConversationEntity();
        entity.conversationId = command.conversationId();
        entity.ownerUserId = command.ownerUserId();
        entity.vehicleZone = command.vehicleZone();
        entity.title = command.title();
        long now = System.currentTimeMillis();
        entity.createdAtMs = now;
        entity.updatedAtMs = now;
        entity.archivedAtMs = null;
        entity.schemaVersion = command.schemaVersion();
        transaction.runInTransaction(() -> {
            conversations.upsert(entity);
            conversations.touchUpdated(entity.conversationId, now);
        });
        return toRow(entity);
    }

    @Override
    public ConversationRow findConversation(String conversationId) {
        ConversationEntity entity = conversations.getById(conversationId);
        return entity == null ? null : toRow(entity);
    }

    @Override
    public List<ConversationRow> listConversations(String ownerUserId, boolean includeArchived,
            int limit) {
        List<ConversationEntity> entities =
                conversations.listByOwner(ownerUserId, includeArchived, limit);
        List<ConversationRow> rows = new ArrayList<>(entities.size());
        for (ConversationEntity entity : entities) {
            rows.add(toRow(entity));
        }
        return rows;
    }

    // ---- 消息读取 ----

    @Override
    public MessageRow findMessage(String messageId) {
        ConversationMessageEntity entity = messages.getById(messageId);
        return entity == null ? null : toRow(entity);
    }

    @Override
    public MessageRow findMessageByIdempotencyKey(String idempotencyKey) {
        ConversationMessageEntity entity = messages.getByIdempotencyKey(idempotencyKey);
        return entity == null ? null : toRow(entity);
    }

    @Override
    public List<MessageRow> latestMessages(String conversationId, int limit) {
        List<ConversationMessageEntity> descending = messages.latestDescending(conversationId,
                limit);
        return ascending(descending);
    }

    @Override
    public List<MessageRow> latestCompletedForSeed(String conversationId, int maxEntries) {
        List<ConversationMessageEntity> descending = messages.latestCompletedDescending(
                conversationId, PersistedMessageStatus.COMPLETED.wire(),
                ConversationMessage.ROLE_USER, ConversationMessage.ROLE_ASSISTANT, maxEntries);
        return ascending(descending);
    }

    @Override
    public MessagePage pageMessages(String conversationId, long beforeSequenceExclusive,
            int limit) {
        // 多取一条判定 hasMore；before=-1 表示从最新向前。
        List<ConversationMessageEntity> descending =
                messages.pageBeforeDescending(conversationId, beforeSequenceExclusive,
                        limit + 1);
        boolean hasMore = descending.size() > limit;
        List<ConversationMessageEntity> page = hasMore
                ? descending.subList(0, limit) : descending;
        return new MessagePage(ascending(page), hasMore);
    }

    // ---- 提交 ----

    @Override
    public SubmittedUserMessage submitUserMessage(UserSubmission command) {
        final SubmittedUserMessage[] result = new SubmittedUserMessage[1];
        transaction.runInTransaction(() -> {
            ConversationMessageEntity existing =
                    command.idempotencyKey() == null ? null
                            : messages.getByIdempotencyKey(command.idempotencyKey());
            if (existing != null) {
                result[0] = new SubmittedUserMessage(existing.sequenceNo, true);
                return;
            }
            ConversationEntity conversation = conversations.getById(command.conversationId());
            if (conversation == null) {
                throw new IllegalArgumentException("conversation 不存在: " + command.conversationId());
            }
            if (conversation.archivedAtMs != null) {
                throw new IllegalArgumentException("conversation 已归档: " + command.conversationId());
            }
            Long maxSequence = messages.maxSequence(command.conversationId());
            long sequence = (maxSequence == null ? 0L : maxSequence) + 1L;
            long now = System.currentTimeMillis();

            ConversationMessageEntity message = new ConversationMessageEntity();
            message.messageId = command.messageId();
            message.conversationId = command.conversationId();
            message.sequenceNo = sequence;
            message.role = ConversationMessage.ROLE_USER;
            message.status = PersistedMessageStatus.ACCEPTED.wire();
            message.channel = command.channelWire();
            message.text = command.text();
            message.languageTag = command.languageTag();
            message.conversationTaskId = command.conversationTaskId();
            message.replyToMessageId = null;
            message.failureCode = 0;
            message.createdAtMs = now;
            message.updatedAtMs = now;
            message.idempotencyKey = command.idempotencyKey();
            message.schemaVersion = WIRE_SCHEMA_VERSION;
            messages.upsert(message);

            ConversationTaskLinkEntity link = new ConversationTaskLinkEntity();
            link.conversationTaskId = command.conversationTaskId();
            link.runtimeRequestId = command.runtimeRequestId();
            link.conversationId = command.conversationId();
            link.userMessageId = command.messageId();
            link.assistantMessageId = null;
            link.readOnlyHint = command.readOnlyHint();
            link.terminalStatus = null;
            link.createdAtMs = now;
            link.startedAtMs = null;
            link.terminalAtMs = null;
            links.upsert(link);

            conversations.touchUpdated(command.conversationId(), now);
            result[0] = new SubmittedUserMessage(sequence, false);
        });
        return result[0];
    }

    // ---- 执行期 ----

    @Override
    public boolean markRunning(String conversationTaskId) {
        final boolean[] marked = {false};
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            // Link 的读取必须在事务内：clearUserData 可与 lane 出队并发，事务外读取会让
            // 已删除会话上的旧任务重新写入 RUNNING。
            ConversationTaskLinkEntity link = links.getById(conversationTaskId);
            if (link == null || link.terminalStatus != null
                    || conversations.getById(link.conversationId) == null) {
                return;
            }
            links.markStarted(conversationTaskId, now);
            messages.updateStatus(link.userMessageId, PersistedMessageStatus.RUNNING.wire(),
                    0, now);
            conversations.touchUpdated(link.conversationId, now);
            marked[0] = true;
        });
        return marked[0];
    }

    @Override
    public boolean writeTerminal(TerminalWrite command) {
        final boolean[] written = {false};
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            // 与 clearForUsers 共用同一事务边界，避免“先查到 link，后被清库，再插 assistant”
            // 的 TOCTOU 泄露。不存在/已终态的 link 都是幂等 no-op。
            ConversationTaskLinkEntity link = links.getById(command.conversationTaskId());
            if (link == null || link.terminalStatus != null
                    || conversations.getById(link.conversationId) == null) {
                Log.w(TAG, "[ConversationStore] conversation 已清除或任务已终态，丢弃终态 task="
                        + command.conversationTaskId());
                return;
            }
            String assistantMessageId = null;
            if (command.assistantText() != null && !command.assistantText().isBlank()) {
                Long maxSequence = messages.maxSequence(link.conversationId);
                long sequence = (maxSequence == null ? 0L : maxSequence) + 1L;
                ConversationMessageEntity assistant = new ConversationMessageEntity();
                assistant.messageId = command.assistantMessageId();
                assistant.conversationId = link.conversationId;
                assistant.sequenceNo = sequence;
                assistant.role = ConversationMessage.ROLE_ASSISTANT;
                assistant.status = command.userStatusWire();
                assistant.channel = MessageRow.CHANNEL_NONE_WIRE;
                assistant.text = command.assistantText();
                assistant.languageTag = null;
                assistant.conversationTaskId = command.conversationTaskId();
                assistant.replyToMessageId = link.userMessageId;
                assistant.failureCode = command.failureCode();
                assistant.createdAtMs = now;
                assistant.updatedAtMs = now;
                assistant.idempotencyKey = null;
                assistant.schemaVersion = WIRE_SCHEMA_VERSION;
                messages.upsert(assistant);
                assistantMessageId = assistant.messageId;
            }
            messages.updateStatus(link.userMessageId, command.userStatusWire(),
                    command.failureCode(), now);
            links.writeTerminal(command.conversationTaskId(), command.userStatusWire(),
                    assistantMessageId, now);
            conversations.touchUpdated(link.conversationId, now);
            written[0] = true;
        });
        return written[0];
    }

    @Override
    public long appendSystemNote(String conversationId, String text) {
        final long[] sequenceOut = new long[1];
        transaction.runInTransaction(() -> {
            // 恢复扫描可能与清库交错；系统说明不是特权写入，目标线程不存在即不得创建孤儿行。
            if (conversations.getById(conversationId) == null) {
                return;
            }
            Long maxSequence = messages.maxSequence(conversationId);
            long sequence = (maxSequence == null ? 0L : maxSequence) + 1L;
            long now = System.currentTimeMillis();
            ConversationMessageEntity note = new ConversationMessageEntity();
            note.messageId = ConversationIds.newMessageId();
            note.conversationId = conversationId;
            note.sequenceNo = sequence;
            note.role = ConversationMessage.ROLE_SYSTEM;
            note.status = PersistedMessageStatus.COMPLETED.wire();
            note.channel = MessageRow.CHANNEL_NONE_WIRE;
            note.text = text;
            note.languageTag = null;
            note.conversationTaskId = null;
            note.replyToMessageId = null;
            note.failureCode = 0;
            note.createdAtMs = now;
            note.updatedAtMs = now;
            note.idempotencyKey = null;
            note.schemaVersion = WIRE_SCHEMA_VERSION;
            messages.upsert(note);
            conversations.touchUpdated(conversationId, now);
            sequenceOut[0] = sequence;
        });
        return sequenceOut[0];
    }

    // ---- 恢复 / 清理 ----

    @Override
    public List<InterruptedLink> loadNonTerminalLinks() {
        List<ConversationTaskLinkEntity> entities = links.loadNonTerminal();
        List<InterruptedLink> rows = new ArrayList<>(entities.size());
        for (ConversationTaskLinkEntity entity : entities) {
            rows.add(new InterruptedLink(entity.conversationTaskId, entity.conversationId,
                    entity.userMessageId, entity.readOnlyHint));
        }
        return rows;
    }

    @Override
    public void writeRecoveryOutcome(String conversationTaskId, int userStatusWire,
            int failureCode) {
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            ConversationTaskLinkEntity link = links.getById(conversationTaskId);
            if (link == null || link.terminalStatus != null) {
                return; // 幂等：并发/重复对账只生效一次
            }
            messages.updateStatus(link.userMessageId, userStatusWire, failureCode, now);
            links.writeTerminal(conversationTaskId, userStatusWire, null, now);
        });
    }

    @Override
    public String findRunningTaskId(String conversationId) {
        ConversationTaskLinkEntity running = links.findRunning(conversationId);
        return running == null ? null : running.conversationTaskId;
    }

    @Override
    public int clearForUsers(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        final Integer[] removed = {0};
        transaction.runInTransaction(() -> {
            // 两张子表的删除条件依赖 conversation 表；先删父表会让子表子查询为空，
            // 留下未加外键级联保护的敏感正文与 task link。
            messages.deleteByOwnerUsers(userIds);
            links.deleteByOwnerUsers(userIds);
            removed[0] = conversations.deleteByOwners(userIds);
        });
        return removed[0];
    }

    // ---- 映射 ----

    private static ConversationRow toRow(ConversationEntity entity) {
        return new ConversationRow(entity.conversationId, entity.ownerUserId,
                entity.vehicleZone, entity.title, entity.archivedAtMs != null,
                entity.createdAtMs, entity.updatedAtMs);
    }

    private static MessageRow toRow(ConversationMessageEntity entity) {
        return new MessageRow(entity.messageId, entity.conversationId, entity.sequenceNo,
                entity.role, entity.status,
                entity.channel == null ? MessageRow.CHANNEL_NONE_WIRE : entity.channel,
                entity.text, entity.languageTag, entity.conversationTaskId,
                entity.failureCode, entity.createdAtMs, entity.updatedAtMs);
    }

    private static List<MessageRow> ascending(List<ConversationMessageEntity> descending) {
        List<MessageRow> rows = new ArrayList<>(descending.size());
        for (int i = descending.size() - 1; i >= 0; i--) {
            rows.add(toRow(descending.get(i)));
        }
        return Collections.unmodifiableList(rows);
    }
}
