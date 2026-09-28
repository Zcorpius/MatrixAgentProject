package com.matrix.agent.data.embedding;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.RoomMemoryStore;
import com.matrix.agent.embedding.MemoryVectorStore;
import com.matrix.agent.embedding.VectorMath;
import java.util.ArrayList;
import java.util.List;

public final class RoomMemoryVectorStore implements MemoryVectorStore {
    private final MatrixDatabase db;
    public RoomMemoryVectorStore(MatrixDatabase db) { this.db = db; }
    @Override public long currentEpoch() {
        var row = db.memoryRecordDao().queryByKey(RoomMemoryStore.SYSTEM_USER,
                RoomMemoryStore.SYSTEM_ZONE, RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
        return row == null ? 0 : Long.parseLong(row.value);
    }
    @Override public List<Source> sources(MemoryScope scope, long epoch) {
        return db.runInTransaction(() -> {
            if (currentEpoch() != epoch) return List.of();
            List<Source> result = new ArrayList<>();
            for (var row : db.memoryRecordDao().queryByUserZoneLayer(scope.getUserId(),
                    scope.getZone().wireValue(), "semantic")) result.add(source(scope, row, epoch));
            return List.copyOf(result);
        });
    }
    @Override public List<Indexed> load(MemoryScope scope, long epoch, String version, int dimension) {
        return db.runInTransaction(() -> {
            if (currentEpoch() != epoch) return List.of();
            List<Indexed> result = new ArrayList<>();
            java.util.Map<String, MemoryRecordEntity> originals = new java.util.HashMap<>();
            for (var original : db.memoryRecordDao().queryByUserZoneLayer(scope.getUserId(),
                    scope.getZone().wireValue(), "semantic")) originals.put(original.key, original);
            for (var row : db.memoryVectorDao().load(scope.getUserId(), scope.getZone().wireValue(), epoch, version, dimension)) {
                var current = originals.get(row.key);
                if (current == null) continue;
                Source source = source(scope, current, epoch);
                if (!source.digest().equals(row.digest)) continue;
                try { result.add(new Indexed(source, version, VectorMath.decode(row.vector, dimension))); }
                catch (IllegalArgumentException corrupt) { /* Backfill replaces corrupt vectors. */ }
            }
            return List.copyOf(result);
        });
    }
    @Override public boolean commit(Source source, String version, float[] vector) {
        if (version == null || !version.matches("[A-Za-z0-9._-]{1,96}")) return false;
        byte[] bytes = VectorMath.encode(vector);
        return db.runInTransaction(() -> {
            if (currentEpoch() != source.epoch()) return false;
            var current = db.memoryRecordDao().queryByKey(source.scope().getUserId(),
                    source.scope().getZone().wireValue(), "semantic", source.key());
            if (current == null || !source(source.scope(), current, source.epoch()).digest().equals(source.digest())) return false;
            MemoryVectorEntity row = new MemoryVectorEntity();
            row.userId = source.scope().getUserId(); row.zone = source.scope().getZone().wireValue();
            row.layer = "semantic"; row.key = source.key(); row.indexVersion = 1; row.modelVersion = version;
            row.dimension = vector.length; row.digest = source.digest(); row.epoch = source.epoch(); row.vector = bytes;
            db.memoryVectorDao().upsert(row);
            return true;
        });
    }
    private static Source source(MemoryScope scope, MemoryRecordEntity row, long epoch) {
        return new Source(scope, row.key, row.value, row.capturedAtMs, epoch);
    }
}
