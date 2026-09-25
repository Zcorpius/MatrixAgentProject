package com.matrix.agent.data.audit;

import android.util.Log;

import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.TrajectoryDao;
import com.matrix.agent.data.db.TrajectoryEntity;

import java.util.ArrayList;
import java.util.List;

/** Owns trajectory and audit_event only. Memory reset belongs to the epoch transaction. */
public final class RoomAuditRepository implements AuditRepository {
    private static final String TAG = "MatrixAgent";
    private final TrajectoryDao trajectoryDao;
    private final MatrixDatabase database;
    private final AuditEventDao auditEventDao;

    public RoomAuditRepository(TrajectoryDao trajectoryDao) {
        this(null, trajectoryDao, null);
    }

    public RoomAuditRepository(MatrixDatabase database, TrajectoryDao trajectoryDao,
            AuditEventDao auditEventDao) {
        if (trajectoryDao == null) throw new IllegalArgumentException("trajectoryDao required");
        this.trajectoryDao = trajectoryDao;
        this.database = database;
        this.auditEventDao = auditEventDao;
    }

    @Override
    public void persist(AuditOutcomeEntry entry) {
        if (entry == null) return;
        try {
            doPersist(entry);
        } catch (Exception ex) {
            // fail-open:仅 log,不抛——保证主任务路径不被 Audit 拖累
            Log.e(TAG, "[Audit] persist FAILED req=" + entry.requestId
                    + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        }
    }

    private void doPersist(AuditOutcomeEntry entry) {
        TrajectoryEntity entity = new TrajectoryEntity();
        entity.requestId = entry.requestId;
        entity.sessionId = entry.sessionId;
        entity.arbitrationKey = entry.arbitrationKey;
        entity.actor = entry.actor;
        entity.zone = entry.zone;
        entity.userId = entry.userId;
        entity.startedMs = entry.startedMs;
        entity.durationMs = entry.durationMs;
        entity.iterationCount = entry.iterationCount;
        entity.totalToolCalls = entry.totalToolCalls;
        entity.successToolCalls = entry.successToolCalls;
        entity.stopReason = entry.stopReason;
        entity.finalState = entry.finalState;
        entity.trajectoryJson = entry.trajectoryJson;
        entity.createdAtMs = System.currentTimeMillis();
        trajectoryDao.insert(entity);
        Log.i(TAG, "[Audit] persist req=" + entity.requestId
                + " state=" + entity.finalState + " iterations=" + entity.iterationCount
                + " durationMs=" + entity.durationMs);
    }

    @Override
    public AuditRecord queryByRequest(String userId, String zone, String requestId) {
        if (requestId == null || userId == null || zone == null) return null;
        try {
            TrajectoryEntity entity = trajectoryDao.queryByRequestScoped(userId, zone, requestId);
            return entity == null ? null : toRecord(entity);
        } catch (Exception ex) {
            Log.e(TAG, "[Audit] queryByRequest FAILED user=" + userId + " zone=" + zone
                    + " req=" + requestId + " cause=" + ex.getClass().getSimpleName(), ex);
            return null;
        }
    }

    @Override
    public List<AuditRecord> queryBySession(String userId, String zone, String sessionId, int limit) {
        if (sessionId == null || userId == null || zone == null) return new ArrayList<>();
        try {
            List<TrajectoryEntity> entities = trajectoryDao.queryBySessionScoped(
                    userId, zone, sessionId, limit);
            List<AuditRecord> records = new ArrayList<>(entities.size());
            for (TrajectoryEntity entity : entities) {
                AuditRecord record = toRecord(entity);
                if (record != null) records.add(record);
            }
            return records;
        } catch (Exception ex) {
            Log.e(TAG, "[Audit] queryBySession FAILED user=" + userId + " zone=" + zone
                    + " session=" + sessionId + " cause=" + ex.getClass().getSimpleName(), ex);
            return new ArrayList<>();
        }
    }

    private static AuditRecord toRecord(TrajectoryEntity entity) {
        if (entity == null) return null;
        return new AuditRecord(
                entity.requestId,
                entity.sessionId,
                entity.actor,
                entity.zone,
                entity.userId,
                entity.startedMs,
                entity.durationMs,
                entity.stopReason,
                entity.finalState,
                entity.iterationCount,
                entity.totalToolCalls,
                entity.successToolCalls,
                entity.trajectoryJson);
    }

    /** Never deletes memory tables: doing so after the epoch bump would erase new writes. */
    @Override
    public ClearOutcome clearByUserZone(String userId, String zone) {
        if (userId == null || zone == null) return ClearOutcome.failure("userId/zone required");
        int tables = auditEventDao == null ? 1 : 2;
        int[] deleted = {0};
        Runnable clear = () -> {
            deleted[0] = trajectoryDao.deleteByUserZone(userId, zone);
            if (auditEventDao != null) deleted[0] += auditEventDao.deleteByUserZone(userId, zone);
        };
        try {
            if (database != null) database.runInTransaction(clear);
            else clear.run();
            return ClearOutcome.success(tables, tables, deleted[0]);
        } catch (RuntimeException failure) {
            Log.w(TAG, "[Audit] clear failed cause=" + failure.getClass().getSimpleName());
            return ClearOutcome.failure(failure.getClass().getSimpleName());
        }
    }
}
