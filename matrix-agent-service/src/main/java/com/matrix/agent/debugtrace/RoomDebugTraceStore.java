package com.matrix.agent.debugtrace;

import android.util.Log;

import com.matrix.agent.data.conversation.ConversationTaskLinkEntity;
import com.matrix.agent.data.debugtrace.DebugTraceEventDao;
import com.matrix.agent.data.debugtrace.DebugTraceEventEntity;
import com.matrix.agent.data.db.MatrixDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** SQLCipher-backed {@link DebugTraceStore}; all writes use Host-owned single DB lane. */
public final class RoomDebugTraceStore implements DebugTraceStore {

    private static final String TAG = "MatrixAgent";
    private static final int SCHEMA_VERSION = 1;

    private final MatrixDatabase database;
    private final DebugTraceEventDao events;
    private final Executor dbExecutor;

    public RoomDebugTraceStore(MatrixDatabase database, Executor dbExecutor) {
        if (database == null || dbExecutor == null) {
            throw new IllegalArgumentException("database and dbExecutor required");
        }
        this.database = database;
        this.events = database.debugTraceEventDao();
        this.dbExecutor = dbExecutor;
    }

    @Override
    public void persist(List<DebugTraceEvent> source, Consumer<DebugTraceEvent> delivered) {
        if (source == null || source.isEmpty()) return;
        List<DebugTraceEvent> snapshot = List.copyOf(source);
        dbExecutor.execute(() -> {
            try {
                // 同一 emit 的分片必然拥有同一 runtime request；一次 lookup 即可，且在
                // 事务内写入，clearUserData 与之串行化，不会产生失去会话父项的轨迹行。
                List<DebugTraceEventEntity> rows = new ArrayList<>(snapshot.size());
                List<DebugTraceEvent> resolved = new ArrayList<>(snapshot.size());
                final boolean[] mapped = {false};
                database.runInTransaction(() -> {
                    String runtimeRequestId = snapshot.get(0).taskId;
                    ConversationTaskLinkEntity link = database.conversationTaskLinkDao()
                            .getByRuntimeRequestId(runtimeRequestId);
                    if (link == null) return; // 标题/连接测试等不是对话轮次，不进入会话 UI。
                    for (DebugTraceEvent event : snapshot) {
                        if (!runtimeRequestId.equals(event.taskId)) {
                            // 防御：一个批次绝不混 request，错配时宁可不持久化。
                            Log.w(TAG, "[DebugTrace] mixed runtime request batch dropped");
                            return;
                        }
                        DebugTraceEvent contextual = event.withConversationContext(link.conversationId,
                                link.conversationTaskId, link.userMessageId);
                        rows.add(toEntity(contextual));
                        resolved.add(contextual);
                    }
                    events.insertIgnore(rows);
                    mapped[0] = true;
                });
                if (!mapped[0]) return;
                if (delivered != null) {
                    for (DebugTraceEvent event : resolved) delivered.accept(event);
                }
            } catch (RuntimeException error) {
                // 诊断旁路绝不影响 Agent；日志留事件原貌（此前已经过 Redactor）。
                Log.w(TAG, "[DebugTrace] persistence failed: "
                        + error.getClass().getSimpleName());
            }
        });
    }

    @Override
    public List<DebugTraceEvent> history(String hostUserMessageId, int limit) {
        if (hostUserMessageId == null || hostUserMessageId.isBlank() || limit <= 0) {
            return Collections.emptyList();
        }
        int bounded = Math.min(limit, 1_000);
        List<DebugTraceEventEntity> rows = events.listForHostMessage(hostUserMessageId, bounded);
        List<DebugTraceEvent> result = new ArrayList<>(rows.size());
        for (DebugTraceEventEntity row : rows) result.add(fromEntity(row));
        return result;
    }

    @Override
    public void clearAll() {
        dbExecutor.execute(events::deleteAll);
    }

    private static DebugTraceEventEntity toEntity(DebugTraceEvent event) {
        DebugTraceEventEntity entity = new DebugTraceEventEntity();
        entity.eventId = event.traceId + ":" + event.partIndex;
        entity.runtimeRequestId = event.taskId;
        entity.conversationTaskId = event.conversationTaskId;
        entity.conversationId = event.conversationId;
        entity.hostUserMessageId = event.hostUserMessageId;
        entity.eventSequence = event.eventSequence;
        entity.timestampMs = event.timestampMs;
        entity.phase = event.phase;
        entity.traceId = event.traceId;
        entity.partIndex = event.partIndex;
        entity.partCount = event.partCount;
        entity.payload = event.payload;
        entity.schemaVersion = SCHEMA_VERSION;
        return entity;
    }

    private static DebugTraceEvent fromEntity(DebugTraceEventEntity entity) {
        return new DebugTraceEvent(entity.timestampMs, entity.phase, entity.runtimeRequestId,
                entity.traceId, entity.eventSequence, entity.partIndex, entity.partCount,
                entity.payload, entity.conversationId, entity.conversationTaskId,
                entity.hostUserMessageId);
    }
}
