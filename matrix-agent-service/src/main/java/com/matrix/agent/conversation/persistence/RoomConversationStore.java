package com.matrix.agent.conversation.persistence;

import android.util.Log;

import com.matrix.agent.api.conversation.ConversationInfo;
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

    /** DB 投递态文本（评估 v1.0 §4.3 SteerInputMetadata；wire 常量在 SDK 侧）。 */
    private static final String STEER_DELIVERY_PENDING_TEXT = "PENDING";
    private static final String STEER_DELIVERY_OFFERED_TEXT = "OFFERED";
    private static final String STEER_DELIVERY_FAILED_TEXT = "FAILED";

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
        entity.titleOrigin = ConversationInfo.TITLE_ORIGIN_DEFAULT;
        entity.pinned = false;
        entity.lastInputChannel = ConversationMessage.CHANNEL_NONE;
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
            message.inputKind = MessageRow.INPUT_PRIMARY_WIRE;
            message.steerHostUserMessageId = null;
            message.steerDeliveryState = null;
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

            conversations.touchLastInputChannel(command.conversationId(),
                    command.channelWire());
            conversations.touchUpdated(command.conversationId(), now);
            result[0] = new SubmittedUserMessage(sequence, false);
        });
        return result[0];
    }

    // ---- steer 附属输入 ----

    @Override
    public SubmittedSteerMessage appendSteerMessage(SteerSubmission command) {
        final SubmittedSteerMessage[] result = new SubmittedSteerMessage[1];
        transaction.runInTransaction(() -> {
            ConversationMessageEntity existing =
                    command.idempotencyKey() == null ? null
                            : messages.getByIdempotencyKey(command.idempotencyKey());
            if (existing != null) {
                result[0] = new SubmittedSteerMessage(existing.sequenceNo, true);
                return;
            }
            ConversationEntity conversation = conversations.getById(command.conversationId());
            if (conversation == null || conversation.archivedAtMs != null) {
                throw new IllegalArgumentException(
                        "conversation 不存在或已归档: " + command.conversationId());
            }
            // 线性化（评估 v1.0 §4.3）：宿主运行校验与插入同事务——宿主终态写入经
            // 同一 DB 写锁互斥，不出现“宿主已终态却插入显示 RUNNING 的 steer”。
            ConversationTaskLinkEntity running = links.findRunning(command.conversationId());
            if (running == null || !running.userMessageId.equals(command.hostUserMessageId())) {
                throw new IllegalStateException("宿主任务不在运行中: " + command.conversationId());
            }
            Long maxSequence = messages.maxSequence(command.conversationId());
            long sequence = (maxSequence == null ? 0L : maxSequence) + 1L;
            long now = System.currentTimeMillis();
            ConversationMessageEntity steer = new ConversationMessageEntity();
            steer.messageId = command.messageId();
            steer.conversationId = command.conversationId();
            steer.sequenceNo = sequence;
            steer.role = ConversationMessage.ROLE_USER;
            steer.status = PersistedMessageStatus.RUNNING.wire();
            steer.channel = command.channelWire();
            steer.text = command.text();
            steer.languageTag = command.languageTag();
            // 无 task link / 不触发标题 / 无能力轨迹——附属输入不是第二项任务
            steer.conversationTaskId = null;
            steer.replyToMessageId = null;
            steer.failureCode = 0;
            steer.createdAtMs = now;
            steer.updatedAtMs = now;
            steer.idempotencyKey = command.idempotencyKey();
            steer.inputKind = MessageRow.INPUT_STEER_WIRE;
            steer.steerHostUserMessageId = command.hostUserMessageId();
            steer.steerDeliveryState = STEER_DELIVERY_PENDING_TEXT;
            steer.schemaVersion = WIRE_SCHEMA_VERSION;
            messages.upsert(steer);
            conversations.touchLastInputChannel(command.conversationId(),
                    command.channelWire());
            conversations.touchUpdated(command.conversationId(), now);
            result[0] = new SubmittedSteerMessage(sequence, false);
        });
        return result[0];
    }

    @Override
    public void updateSteerDelivery(String messageId, boolean offered) {
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            if (offered) {
                messages.updateSteerDelivery(messageId, STEER_DELIVERY_OFFERED_TEXT, now);
                return;
            }
            // 拒绝投递：附属输入自收敛 FAILED（“未能并入宿主请求”），不再随宿主镜像
            messages.updateSteerDelivery(messageId, STEER_DELIVERY_FAILED_TEXT, now);
            messages.updateStatus(messageId, PersistedMessageStatus.FAILED.wire(), 0, now);
        });
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
                assistant.inputKind = MessageRow.INPUT_PRIMARY_WIRE;
                assistant.steerHostUserMessageId = null;
                assistant.steerDeliveryState = null;
                assistant.schemaVersion = WIRE_SCHEMA_VERSION;
                messages.upsert(assistant);
                assistantMessageId = assistant.messageId;
            }
            messages.updateStatus(link.userMessageId, command.userStatusWire(),
                    command.failureCode(), now);
            // 附属 steer 镜像收敛（评估 v1.0 §4.3）：仍 RUNNING 的 steer 与宿主同事务
            // 收敛；投递态保留原值（OFFERED 才声称“已并入”，PENDING 显示“未确认”）。
            messages.convergeSteersByHost(link.userMessageId, command.userStatusWire(),
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
            note.inputKind = MessageRow.INPUT_PRIMARY_WIRE;
            note.steerHostUserMessageId = null;
            note.steerDeliveryState = null;
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
            messages.convergeSteersByHost(link.userMessageId, userStatusWire, failureCode, now);
            links.writeTerminal(conversationTaskId, userStatusWire, null, now);
        });
    }

    @Override
    public String findRunningTaskId(String conversationId) {
        ConversationTaskLinkEntity running = links.findRunning(conversationId);
        return running == null ? null : running.conversationTaskId;
    }

    @Override
    public String findRunningUserMessageId(String conversationId) {
        ConversationTaskLinkEntity running = links.findRunning(conversationId);
        return running == null ? null : running.userMessageId;
    }

    // ---- 用户组织（阶段 3） ----

    @Override
    public void upsertAnnotation(AnnotationUpsert command) {
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            if (messages.getById(command.messageId()) == null) {
                throw new IllegalArgumentException(
                        "message 不存在: " + command.messageId());
            }
            com.matrix.agent.data.conversation.ConversationMessageAnnotationEntity entity =
                    new com.matrix.agent.data.conversation.ConversationMessageAnnotationEntity();
            entity.messageId = command.messageId();
            entity.ownerUserId = command.ownerUserId();
            entity.favorite = command.favorite();
            entity.userNote = command.userNote();
            entity.createdAtMs = now;
            entity.updatedAtMs = now;
            database.conversationMessageAnnotationDao().upsert(entity);
        });
    }

    @Override
    public AnnotationRow findAnnotation(String messageId, String ownerUserId) {
        com.matrix.agent.data.conversation.ConversationMessageAnnotationEntity entity =
                database.conversationMessageAnnotationDao().get(messageId, ownerUserId);
        return entity == null ? null : new AnnotationRow(entity.messageId,
                entity.ownerUserId, entity.favorite, entity.userNote, entity.updatedAtMs);
    }

    @Override
    public void recordQuote(QuoteRecord command) {
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            if (messages.getById(command.quotedMessageId()) == null) {
                throw new IllegalArgumentException(
                        "被引消息不存在: " + command.quotedMessageId());
            }
            com.matrix.agent.data.conversation.ConversationQuoteEntity entity =
                    new com.matrix.agent.data.conversation.ConversationQuoteEntity();
            entity.messageId = command.messageId();
            entity.quotedMessageId = command.quotedMessageId();
            entity.quoteSnapshot = command.snapshot();
            entity.createdAtMs = now;
            database.conversationQuoteDao().upsert(entity);
        });
    }

    @Override
    public QuoteRow findQuoteByQuotingMessage(String messageId) {
        com.matrix.agent.data.conversation.ConversationQuoteEntity entity =
                database.conversationQuoteDao().getByQuotingMessage(messageId);
        return entity == null ? null
                : new QuoteRow(entity.messageId, entity.quotedMessageId,
                        entity.quoteSnapshot);
    }

    @Override
    public void recordLineage(LineageRecord command) {
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            if (conversations.getById(command.parentConversationId()) == null) {
                throw new IllegalArgumentException(
                        "父会话不存在: " + command.parentConversationId());
            }
            ConversationEntity child = new ConversationEntity();
            child.conversationId = command.childConversationId();
            child.ownerUserId = command.createdByUser();
            child.vehicleZone = "DRIVER";
            child.title = null;
            child.createdAtMs = now;
            child.updatedAtMs = now;
            child.archivedAtMs = null;
            child.titleOrigin = ConversationInfo.TITLE_ORIGIN_DEFAULT;
            child.pinned = false;
            child.lastInputChannel = ConversationMessage.CHANNEL_NONE;
            child.schemaVersion = WIRE_SCHEMA_VERSION;
            conversations.upsert(child);

            com.matrix.agent.data.conversation.ConversationLineageEntity lineage =
                    new com.matrix.agent.data.conversation.ConversationLineageEntity();
            lineage.childConversationId = command.childConversationId();
            lineage.parentConversationId = command.parentConversationId();
            lineage.forkSequenceNo = command.forkSequenceNo();
            lineage.parentTitleAtFork = command.parentTitleAtFork();
            lineage.seedSnapshot = command.seedSnapshotJson();
            lineage.seedVersion = 1;
            lineage.createdByUser = command.createdByUser();
            lineage.createdAtMs = now;
            database.conversationLineageDao().upsert(lineage);
        });
    }

    @Override
    public LineageRow findLineage(String childConversationId) {
        com.matrix.agent.data.conversation.ConversationLineageEntity entity =
                database.conversationLineageDao().getByChild(childConversationId);
        return entity == null ? null
                : new LineageRow(entity.childConversationId, entity.parentConversationId,
                        entity.forkSequenceNo, entity.parentTitleAtFork,
                        entity.seedSnapshot, entity.seedVersion);
    }

    // ---- 定向窗口 / 重命名 ----

    @Override
    public MessageWindow windowAfter(String conversationId, long afterSequenceExclusive,
            int limit) {
        // limit+1 判定 hasAfter；hasBefore = 起点前是否还有更早消息
        List<ConversationMessageEntity> ascending = messages.pageAfterAscending(
                conversationId, afterSequenceExclusive, limit + 1);
        boolean hasAfter = ascending.size() > limit;
        List<ConversationMessageEntity> page =
                hasAfter ? ascending.subList(0, limit) : ascending;
        boolean hasBefore = !page.isEmpty()
                && messages.countBefore(conversationId, page.get(0).sequenceNo) > 0;
        return new MessageWindow(ascendingRows(page), hasBefore, hasAfter, true);
    }

    @Override
    public MessageWindow windowAround(String conversationId, long anchorSequence, int limit) {
        if (messages.countBySequence(conversationId, anchorSequence) == 0) {
            return MessageWindow.anchorMissing();
        }
        int beforeCount = limit / 2;
        int afterCount = limit - beforeCount;
        // 前半窗复用向前翻页（降序多取一条判定 hasBefore），后半窗从锚点（含）向后取
        List<ConversationMessageEntity> olderDescending = messages.pageBeforeDescending(
                conversationId, anchorSequence, beforeCount + 1);
        boolean hasBefore = olderDescending.size() > beforeCount;
        List<ConversationMessageEntity> older = hasBefore
                ? olderDescending.subList(0, beforeCount) : olderDescending;
        List<ConversationMessageEntity> newer = messages.windowFromAnchorAscending(
                conversationId, anchorSequence, afterCount + 1);
        boolean hasAfter = newer.size() > afterCount;
        List<ConversationMessageEntity> tail =
                hasAfter ? newer.subList(0, afterCount) : newer;
        List<ConversationMessageEntity> window = new ArrayList<>(older.size() + tail.size());
        for (int i = older.size() - 1; i >= 0; i--) {
            window.add(older.get(i));
        }
        window.addAll(tail);
        return new MessageWindow(ascendingRows(window), hasBefore, hasAfter, true);
    }

    @Override
    public boolean autoTitleIfDefault(String conversationId, String title) {
        return conversations.autoTitleIfDefault(conversationId, title,
                System.currentTimeMillis()) > 0;
    }

    @Override
    public boolean renameConversation(String conversationId, String title) {
        final boolean[] renamed = {false};
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            if (conversations.getById(conversationId) == null) {
                return;
            }
            conversations.rename(conversationId, title, now);
            renamed[0] = true;
        });
        return renamed[0];
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
                entity.createdAtMs, entity.updatedAtMs, entity.titleOrigin, entity.pinned,
                entity.lastInputChannel);
    }

    private static MessageRow toRow(ConversationMessageEntity entity) {
        return new MessageRow(entity.messageId, entity.conversationId, entity.sequenceNo,
                entity.role, entity.status,
                entity.channel == null ? MessageRow.CHANNEL_NONE_WIRE : entity.channel,
                entity.text, entity.languageTag, entity.conversationTaskId,
                entity.failureCode, entity.createdAtMs, entity.updatedAtMs,
                entity.inputKind, entity.steerHostUserMessageId,
                deliveryToWire(entity.steerDeliveryState));
    }

    private static List<MessageRow> ascendingRows(List<ConversationMessageEntity> ascending) {
        List<MessageRow> rows = new ArrayList<>(ascending.size());
        for (ConversationMessageEntity entity : ascending) {
            rows.add(toRow(entity));
        }
        return Collections.unmodifiableList(rows);
    }

    private static int deliveryToWire(String state) {
        if (state == null) return MessageRow.STEER_DELIVERY_NONE_WIRE;
        switch (state) {
            case STEER_DELIVERY_PENDING_TEXT: return ConversationMessage.STEER_DELIVERY_PENDING;
            case STEER_DELIVERY_OFFERED_TEXT: return ConversationMessage.STEER_DELIVERY_OFFERED;
            case STEER_DELIVERY_FAILED_TEXT: return ConversationMessage.STEER_DELIVERY_FAILED;
            default: return MessageRow.STEER_DELIVERY_NONE_WIRE;
        }
    }

    private static List<MessageRow> ascending(List<ConversationMessageEntity> descending) {
        List<MessageRow> rows = new ArrayList<>(descending.size());
        for (int i = descending.size() - 1; i >= 0; i--) {
            rows.add(toRow(descending.get(i)));
        }
        return Collections.unmodifiableList(rows);
    }
}
