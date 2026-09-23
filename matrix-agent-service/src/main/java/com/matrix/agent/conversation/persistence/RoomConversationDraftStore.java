package com.matrix.agent.conversation.persistence;

import android.util.Log;

import com.matrix.agent.conversation.ConversationDraftStore;
import com.matrix.agent.data.conversation.ConversationConsumedDraftEntity;
import com.matrix.agent.data.conversation.ConversationDraftDao;
import com.matrix.agent.data.conversation.ConversationDraftEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ConversationDraftStore} 的 Room 投影。条件写入规则在事务内以 DAO 原语组装；
 * tombstone 清理按 (owner, zone, conversation) 分组在 Java 侧排序判定（SQLite 版本
 * 无关），量级为每会话数百条以内。
 */
public final class RoomConversationDraftStore implements ConversationDraftStore {

    private static final String TAG = "MatrixAgent";
    private static final long TOMBSTONE_TTL_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int TOMBSTONE_KEEP_PER_CONVERSATION = 128;

    public interface TransactionRunner {
        void runInTransaction(Runnable body);
    }

    private final ConversationDraftDao dao;
    private final TransactionRunner transaction;

    public RoomConversationDraftStore(ConversationDraftDao dao, TransactionRunner transaction) {
        this.dao = dao;
        this.transaction = transaction;
    }

    @Override
    public DraftRow get(String ownerUserId, String vehicleZone, String conversationId) {
        ConversationDraftEntity entity = dao.getDraft(ownerUserId, vehicleZone, conversationId);
        return entity == null ? null : toRow(entity);
    }

    @Override
    public SaveResult save(SaveCommand command) {
        if (command.text() == null
                || command.text().getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES
                || command.selectionStart() < 0 || command.selectionEnd() < command.selectionStart()
                || command.selectionEnd() > command.text().length()
                || command.draftInstanceId() == null || command.draftInstanceId().isBlank()) {
            return SaveResult.REJECTED_PAYLOAD;
        }
        final SaveResult[] result = new SaveResult[1];
        transaction.runInTransaction(() -> result[0] = saveLocked(command));
        return result[0];
    }

    private SaveResult saveLocked(SaveCommand command) {
        if (dao.countConsumed(command.ownerUserId(), command.vehicleZone(),
                command.conversationId(), command.draftInstanceId()) > 0) {
            // 草稿复活防线（§7.2）：该 instance 已被提交消费，迟到的旧保存一律拒绝。
            Log.i(TAG, "[Draft] 拒绝已消费 instance conv=" + command.conversationId());
            return SaveResult.REJECTED_CONSUMED_INSTANCE;
        }
        ConversationDraftEntity existing = dao.getDraft(command.ownerUserId(),
                command.vehicleZone(), command.conversationId());
        if (existing != null && existing.draftInstanceId.equals(command.draftInstanceId())
                && command.revision() <= existing.revision) {
            return SaveResult.IDEMPOTENT_STALE_REVISION;
        }
        ConversationDraftEntity entity = new ConversationDraftEntity();
        entity.ownerUserId = command.ownerUserId();
        entity.vehicleZone = command.vehicleZone();
        entity.conversationId = command.conversationId();
        entity.draftInstanceId = command.draftInstanceId();
        entity.revision = command.revision();
        entity.text = command.text();
        entity.selectionStart = command.selectionStart();
        entity.selectionEnd = command.selectionEnd();
        entity.updatedAtMs = command.updatedAtMs();
        dao.upsertDraft(entity);
        return SaveResult.SAVED;
    }

    @Override
    public void discard(String ownerUserId, String vehicleZone, String conversationId,
            String draftInstanceId) {
        long now = System.currentTimeMillis();
        transaction.runInTransaction(() -> {
            ConversationDraftEntity existing = dao.getDraft(ownerUserId, vehicleZone,
                    conversationId);
            if (existing == null || !existing.draftInstanceId.equals(draftInstanceId)) {
                return; // 并发新草稿不被误删
            }
            dao.deleteDraft(ownerUserId, vehicleZone, conversationId);
            insertTombstone(ownerUserId, vehicleZone, conversationId, draftInstanceId, now);
        });
    }

    @Override
    public void consumeSubmittedInCallerTransaction(String ownerUserId, String vehicleZone,
            String conversationId, String draftInstanceId) {
        if (draftInstanceId == null || draftInstanceId.isBlank()) return;
        // 调用方事务内执行：不得再包 runInTransaction（嵌套事务语义不属本端口契约）。
        // 关键不是“删当前行”，而是 tombstone 提交快照的确切 instance：新的编辑
        // 生命周期已经先落库时不能被旧提交误删；旧保存还未到时也会被 tombstone 拒绝。
        ConversationDraftEntity existing = dao.getDraft(ownerUserId, vehicleZone, conversationId);
        if (existing != null && draftInstanceId.equals(existing.draftInstanceId)) {
            dao.deleteDraft(ownerUserId, vehicleZone, conversationId);
        }
        insertTombstone(ownerUserId, vehicleZone, conversationId,
                draftInstanceId, System.currentTimeMillis());
    }

    private void insertTombstone(String ownerUserId, String vehicleZone, String conversationId,
            String draftInstanceId, long consumedAtMs) {
        ConversationConsumedDraftEntity tombstone = new ConversationConsumedDraftEntity();
        tombstone.ownerUserId = ownerUserId;
        tombstone.vehicleZone = vehicleZone;
        tombstone.conversationId = conversationId;
        tombstone.draftInstanceId = draftInstanceId;
        tombstone.consumedAtMs = consumedAtMs;
        dao.insertConsumed(tombstone);
    }

    @Override
    public void clearForUsers(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        transaction.runInTransaction(() -> {
            dao.deleteDraftsByUsers(userIds);
            dao.deleteConsumedByUsers(userIds);
        });
    }

    @Override
    public int cleanupTombstones(long nowMs) {
        List<ConversationConsumedDraftEntity> all = dao.listAllConsumed();
        Map<String, List<ConversationConsumedDraftEntity>> byConversation = new HashMap<>();
        for (ConversationConsumedDraftEntity entity : all) {
            byConversation.computeIfAbsent(entity.ownerUserId + "\u0000" + entity.vehicleZone
                    + "\u0000" + entity.conversationId, ignored -> new ArrayList<>())
                    .add(entity);
        }
        final int[] removed = {0};
        List<ConversationConsumedDraftEntity> doomed = new ArrayList<>();
        for (List<ConversationConsumedDraftEntity> group : byConversation.values()) {
            // 新→旧排序：时间或数量任一上界触发即回收。提交/保存已在 Launcher
            // per-conversation lane 全序，Host 的 tombstone 只需覆盖在途命令窗口，
            // 不应把“最近 128 条”误当作永久保底集而让低频会话无限积累。
            group.sort(Comparator.comparingLong(
                    (ConversationConsumedDraftEntity entity) -> entity.consumedAtMs).reversed());
            for (int rank = 0; rank < group.size(); rank++) {
                ConversationConsumedDraftEntity entity = group.get(rank);
                boolean exceedsCount = rank >= TOMBSTONE_KEEP_PER_CONVERSATION;
                boolean expired = nowMs - entity.consumedAtMs > TOMBSTONE_TTL_MS;
                if (exceedsCount || expired) doomed.add(entity);
            }
        }
        if (doomed.isEmpty()) return 0;
        transaction.runInTransaction(() -> {
            for (ConversationConsumedDraftEntity entity : doomed) {
                dao.deleteConsumed(entity.ownerUserId, entity.vehicleZone,
                        entity.conversationId, entity.draftInstanceId);
            }
            removed[0] = doomed.size();
        });
        if (removed[0] > 0) {
            Log.i(TAG, "[Draft] tombstone 清理 removed=" + removed[0]);
        }
        return removed[0];
    }

    private static DraftRow toRow(ConversationDraftEntity entity) {
        return new DraftRow(entity.conversationId, entity.draftInstanceId, entity.revision,
                entity.text, entity.selectionStart, entity.selectionEnd, entity.updatedAtMs);
    }
}
