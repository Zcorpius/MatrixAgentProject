package com.matrix.agent.host.di;

import android.content.Context;
import android.util.Log;

import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.data.audit.NoopAuditRepository;
import com.matrix.agent.data.audit.RoomAuditRepository;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.platform.KeystoreHmacAuditDigest;
import com.matrix.agent.platform.AuditDigest;
import com.matrix.agent.platform.UnavailableAuditDigest;

import java.util.concurrent.ScheduledExecutorService;

/** Owns audit persistence, privacy digest and asynchronous audit-event lifecycle. */
final class AuditRuntimeGraph {
    private static final String TAG = "MatrixAgent";

    private final AuditRepository repository;
    private final AuditDigest digest;
    private final AuditEventRecorder eventRecorder;

    AuditRuntimeGraph(Context context, MatrixDatabase database, ScheduledExecutorService scheduler) {
        repository = createRepository(database);
        digest = createDigest(context);
        eventRecorder = createEventRecorder(database, scheduler);
    }

    AuditRepository repository() { return repository; }
    AuditDigest digest() { return digest; }
    AuditEventRecorder eventRecorder() { return eventRecorder; }

    private static AuditRepository createRepository(MatrixDatabase database) {
        if (database == null) return NoopAuditRepository.INSTANCE;
        try {
            return new RoomAuditRepository(database, database.trajectoryDao(),
                    database.sessionHistoryDao(), database.memoryRecordDao(), database.auditEventDao());
        } catch (Exception error) {
            Log.e(TAG, "[AuditGraph] audit repository unavailable; using NOOP", error);
            return NoopAuditRepository.INSTANCE;
        }
    }

    private static AuditEventRecorder createEventRecorder(MatrixDatabase database,
            ScheduledExecutorService scheduler) {
        if (database == null) return AuditEventRecorder.NOOP;
        try {
            return new AuditEventRecorder(database.auditEventDao(), scheduler);
        } catch (Exception error) {
            Log.w(TAG, "[AuditGraph] event recorder unavailable; using NOOP", error);
            return AuditEventRecorder.NOOP;
        }
    }

    private static AuditDigest createDigest(Context context) {
        try {
            return new KeystoreHmacAuditDigest(context);
        } catch (Exception error) {
            Log.w(TAG, "[AuditGraph] HMAC unavailable; using non-correlatable digest", error);
            return new UnavailableAuditDigest();
        }
    }
}
