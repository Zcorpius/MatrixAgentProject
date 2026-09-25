package com.matrix.agent.session;

import android.util.Log;
import com.matrix.agent.identity.VehicleZone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded, access-ordered session context store with idle expiration. */
public final class SessionManager {
    private static final String TAG = "MatrixAgent";
    private static final long DEFAULT_TTL_MILLIS = 30L * 60L * 1_000L;
    private static final int DEFAULT_MAX_SESSIONS = 32;

    private final long ttlMillis;
    private final int maxSessions;
    private final LongSupplier clock;
    private final LinkedHashMap<String, SessionEntry> sessions = new LinkedHashMap<>(16, 0.75f, true);

    public SessionManager() {
        this(DEFAULT_TTL_MILLIS, DEFAULT_MAX_SESSIONS, System::currentTimeMillis);
    }

    public SessionManager(long ttlMillis, int maxSessions, LongSupplier clock) {
        if (ttlMillis <= 0) throw new IllegalArgumentException("ttlMillis 必须大于 0");
        if (maxSessions <= 0) throw new IllegalArgumentException("maxSessions 必须大于 0");
        this.ttlMillis = ttlMillis;
        this.maxSessions = maxSessions;
        this.clock = clock;
        Log.i(TAG, "[Session] init ttlMs=" + ttlMillis + " maxSessions=" + maxSessions);
    }

    public synchronized SessionContext getOrCreate(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) throw new IllegalArgumentException("sessionId 不能为空");
        long now = clock.getAsLong();
        int expired = removeExpired(now);
        if (expired > 0) {
            Log.d(TAG, "[Session] expired " + expired + " idle sessions on getOrCreate");
        }
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) {
            evictOldestIfFull();
            entry = new SessionEntry(new SessionContext(), now);
            sessions.put(sessionId, entry);
            Log.d(TAG, "[Session] created new session=" + sessionId
                    + " total=" + sessions.size() + "/" + maxSessions);
        } else {
            entry.lastAccessMillis = now;
            Log.d(TAG, "[Session] reused session=" + sessionId
                    + " turns=" + entry.context.getRecentTurns().size());
        }
        return entry.context;
    }

    /** Binds a task session to one occupant before any memory can be read from it. */
    public synchronized SessionContext bindOrCreate(String sessionId, String userId,
            VehicleZone zone) {
        if (userId == null || userId.isBlank() || zone == null) {
            throw new IllegalArgumentException("session owner required");
        }
        getOrCreate(sessionId);
        SessionEntry entry = sessions.get(sessionId);
        if (entry.ownerUserId == null) {
            // An unbound legacy session may contain turns from another actor; discard them.
            entry = new SessionEntry(new SessionContext(), clock.getAsLong(), userId, zone);
            sessions.put(sessionId, entry);
        } else if (!entry.ownerUserId.equals(userId) || entry.zone != zone) {
            throw new IllegalStateException("session belongs to a different occupant");
        }
        return entry.context;
    }

    public synchronized List<String> getRecentTurnsScoped(String sessionId, String userId,
            VehicleZone zone) {
        removeExpired(clock.getAsLong());
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null || entry.ownerUserId == null || !entry.ownerUserId.equals(userId)
                || entry.zone != zone) return Collections.emptyList();
        entry.lastAccessMillis = clock.getAsLong();
        return entry.context.getRecentTurns();
    }

    /** Returns only the verified climate state of an owner-bound, unexpired session. */
    public synchronized String getClimateSnapshotScoped(String sessionId, String userId,
            VehicleZone zone) {
        removeExpired(clock.getAsLong());
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null || entry.ownerUserId == null || !entry.ownerUserId.equals(userId)
                || entry.zone != zone) return null;
        entry.lastAccessMillis = clock.getAsLong();
        return entry.context.verifiedClimateSnapshot();
    }

    public synchronized List<String> getRecentTurns(String sessionId) {
        long now = clock.getAsLong();
        removeExpired(now);
        SessionEntry entry = sessions.get(sessionId);
        if (entry == null) return Collections.emptyList();
        entry.lastAccessMillis = now;
        return entry.context.getRecentTurns();
    }

    public synchronized Map<String, List<String>> snapshotTurns() {
        removeExpired(clock.getAsLong());
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, SessionEntry> entry : sessions.entrySet()) {
            snapshot.put(entry.getKey(), new ArrayList<>(entry.getValue().context.getRecentTurns()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public synchronized void clear() {
        int removed = sessions.size();
        sessions.clear();
        Log.i(TAG, "[Session] cleared all sessions, removed=" + removed);
    }

    public synchronized int size() {
        removeExpired(clock.getAsLong());
        return sessions.size();
    }

    private int removeExpired(long now) {
        int removed = 0;
        Iterator<Map.Entry<String, SessionEntry>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue().lastAccessMillis >= ttlMillis) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private void evictOldestIfFull() {
        if (sessions.size() < maxSessions) return;
        Iterator<String> iterator = sessions.keySet().iterator();
        if (iterator.hasNext()) {
            String evicted = iterator.next();
            iterator.remove();
            Log.w(TAG, "[Session] capacity reached, evicted oldest session=" + evicted);
        }
    }

    private static final class SessionEntry {
        private final SessionContext context;
        private final String ownerUserId;
        private final VehicleZone zone;
        private long lastAccessMillis;

        private SessionEntry(SessionContext context, long lastAccessMillis) {
            this(context, lastAccessMillis, null, null);
        }

        private SessionEntry(SessionContext context, long lastAccessMillis,
                String ownerUserId, VehicleZone zone) {
            this.context = context;
            this.lastAccessMillis = lastAccessMillis;
            this.ownerUserId = ownerUserId;
            this.zone = zone;
        }
    }
}
