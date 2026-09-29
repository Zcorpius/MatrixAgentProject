package com.matrix.agent.data.failure;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.RoomMemoryStore;
import com.matrix.agent.failure.FailureLesson;
import com.matrix.agent.failure.FailureLessonStore;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RoomFailureLessonStore implements FailureLessonStore {
    public static final long TTL_MILLIS = java.util.concurrent.TimeUnit.DAYS.toMillis(30);
    private final MatrixDatabase db;
    private final FailureLessonDao dao;
    public RoomFailureLessonStore(MatrixDatabase db) { this.db = db; dao = db.failureLessonDao(); }

    private long epoch() {
        var row = db.memoryRecordDao().queryByKey(RoomMemoryStore.SYSTEM_USER,
                RoomMemoryStore.SYSTEM_ZONE, RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
        return row == null ? 0 : Long.parseLong(row.value);
    }

    @Override public boolean save(Entry entry) {
        if (entry.epoch() < 0 || entry.lesson().lessonCode() == FailureLesson.Code.UNKNOWN
                || entry.sourceTask() == null || entry.sourceTask().length() > 128
                || !entry.capability().matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")) return false;
        FailureLessonEntity row = new FailureLessonEntity();
        row.owner = entry.scope().getUserId();
        row.zone = entry.scope().getZone().wireValue();
        row.sourceTask = entry.sourceTask();
        row.epoch = entry.epoch();
        row.version = entry.lesson().version();
        row.capability = entry.capability();
        row.code = entry.lesson().lessonCode().name();
        row.evidenceRefs = new JSONArray(entry.lesson().evidenceRefs()).toString();
        row.createdAt = entry.createdAtMillis();
        row.expiresAt = Math.addExact(row.createdAt, TTL_MILLIS);
        return db.runInTransaction(() -> {
            if (epoch() != entry.epoch()) return false;
            dao.prune(row.createdAt, row.epoch);
            boolean saved = dao.insert(row) != -1;
            dao.trim(row.owner, row.zone);
            return saved;
        });
    }

    @Override public List<Entry> recall(MemoryScope scope, long epoch, Set<String> capabilities, long now, int limit) {
        if (capabilities.isEmpty() || capabilities.size() > 64 || limit <= 0) return List.of();
        if (epoch() != epoch) return List.of();
        List<Entry> result = new ArrayList<>();
        for (var row : dao.recall(scope.getUserId(), scope.getZone().wireValue(), epoch,
                List.copyOf(capabilities), now, Math.min(limit, 3))) {
            try {
                JSONArray raw = new JSONArray(row.evidenceRefs);
                List<Integer> refs = new ArrayList<>();
                for (int i = 0; i < raw.length(); i++) refs.add(raw.getInt(i));
                FailureLesson lesson = new FailureLesson(row.version, FailureLesson.Code.valueOf(row.code), refs);
                if (lesson.lessonCode() != FailureLesson.Code.UNKNOWN) result.add(
                        new Entry(scope, row.sourceTask, row.epoch, row.createdAt, row.capability, lesson));
            } catch (Exception invalidRecord) { /* Unsupported versions and corrupt rows are not advice. */ }
        }
        // Clear/bump can race the two read queries. Never project an older epoch afterward.
        return epoch() == epoch ? List.copyOf(result) : List.of();
    }
    @Override public void delete(MemoryScope scope) { dao.delete(scope.getUserId(), scope.getZone().wireValue()); }
}
