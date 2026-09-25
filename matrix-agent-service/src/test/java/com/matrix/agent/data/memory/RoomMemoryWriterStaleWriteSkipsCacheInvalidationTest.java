package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

/**
 * 顺手优化:stale reject / fail-closed reject 不应触发
 * {@link EpisodicMemorySourceImpl#invalidateCache()}——避免无意义 cache miss。
 *
 * <p>老代码 writeEpisodicOnTerminal 的 try/catch 内无论事务是否真写入,
 * 都调 episodicSource.invalidateCache()。stale reject / fail-closed reject 没改 db 内容,
 * 失效 cache 后下次召回会重新 SELECT,产生无意义 IO。
 *
 * <p>用 {@code boolean[] written = {false}} flag 区分"真写入"与"reject",
 * 仅在 written[0]=true 时失效。
 *
 * <p>测试用间接行为验证:stale reject 后再 recallEpisodic,DAO queryByUserZone 计数不应增加
 * (cache 仍 fill)。对照:same-epoch 真写入后再 recallEpisodic,DAO 计数应增加(cache 失效)。
 */
public final class RoomMemoryWriterStaleWriteSkipsCacheInvalidationTest {

    private static RoomMemoryStore.TransactionRunner syncRunner() {
        return Runnable::run;
    }

    private static void seedEpoch(CountingMemoryDao dao, long epoch) {
        MemoryRecordEntity row = new MemoryRecordEntity();
        row.userId = RoomMemoryStore.SYSTEM_USER;
        row.zone = RoomMemoryStore.SYSTEM_ZONE;
        row.layer = RoomMemoryStore.PREFERENCE_LAYER;
        row.key = RoomMemoryStore.EPOCH_KEY;
        row.value = Long.toString(epoch);
        row.capturedAtMs = System.currentTimeMillis();
        dao.store.add(row);
    }

    private static SessionHistoryEntity makeEpisodicRow(String userId, String zone, String sessionId) {
        SessionHistoryEntity row = new SessionHistoryEntity();
        row.userId = userId;
        row.zone = zone;  // 用 wireValue 格式("driver"),与 EpisodicMemorySourceImpl.recallEpisodic 的 query 一致
        row.sessionId = sessionId;
        row.startedAtMillis = 1L;
        row.actor = "DRIVER";
        row.finalState = "SUCCEEDED";
        row.stopReason = "DONE";
        row.durationMs = 100L;
        row.turnCount = 1;
        row.trajectoryJson = "{\"successfulCapabilities\":[\"vehicle.climate.set_temperature\"]}";
        return row;
    }

    @Test
    public void staleRejectDoesNotInvalidateCache() {
        CountingSessionDao sessionDao = new CountingSessionDao();
        sessionDao.store.add(makeEpisodicRow("demo-driver", "driver", "old-session-1"));
        CountingMemoryDao memoryDao = new CountingMemoryDao();
        seedEpoch(memoryDao, 9L);  // currentEpoch=9
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(sessionDao);
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, source, syncRunner());

        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);

        // 1) first recall → cache miss,DAO query 1 次
        List<MemorySnippet> first = source.recallEpisodic(scope, "上次做过什么", 5);
        assertEquals("first recall: cache miss,DAO query 1 次", 1, sessionDao.queryCount.get());
        assertEquals("first recall: 1 row", 1, first.size());

        // 2) stale reject(requestEpoch=1 != currentEpoch=9) → 不应失效 cache
        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER)
                .sessionId("new-session").epoch(1L).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 1L);
        writer.writeEpisodic(request, EpisodicTestSupport.write(request, outcome, 1L));

        // 3) second recall → 若 cache 仍 fill,DAO 不被调;若失效,DAO 再调 1 次
        List<MemorySnippet> second = source.recallEpisodic(scope, "上次做过什么", 5);
        assertEquals("无缓存路径再次读取数据库", 2, sessionDao.queryCount.get());
        assertEquals("second recall: 仍 1 row", 1, second.size());
    }

    private static final class CountingSessionDao implements SessionHistoryDao {
        @Override public int deleteSanitizedLegacyRows() { return 0; }
        @Override public int deleteExact(String userId, String zone, String sessionId, long startedAtMillis) { return 0; }
        @Override public int deleteByUser(String userId) { return 0; }
        @Override public int deleteOlderThan(String userId, String zone, long cutoff) { return 0; }
        @Override public int retainLatest(String userId, String zone, int keep) { return 0; }

        final AtomicInteger queryCount = new AtomicInteger();
        final List<SessionHistoryEntity> store = new ArrayList<>();
        @Override public void insert(SessionHistoryEntity entity) { store.add(entity); }
        @Override public List<SessionHistoryEntity> queryByUserZone(String u, String z, int l) {
            queryCount.incrementAndGet();
            List<SessionHistoryEntity> matched = new ArrayList<>();
            for (SessionHistoryEntity e : store) {
                if (u.equals(e.userId) && z.equals(e.zone)) {
                    matched.add(e);
                    if (matched.size() >= l) break;
                }
            }
            return matched;
        }
        @Override public List<SessionHistoryEntity> queryBySession(String s) {
            return Collections.emptyList();
        }
        @Override public int deleteByUserZone(String u, String z) { return 0; }
    }

    private static final class CountingMemoryDao implements MemoryRecordDao {
        @Override public java.util.List<String> queryKeysByUserZoneLayer(String u, String z, String l) {
            return queryByUserZoneLayer(u, z, l).stream().map(row -> row.key).toList();
        }
        @Override public int countByUserZoneLayer(String userId, String zone, String layer) { return queryByUserZoneLayer(userId, zone, layer).size(); }

        @Override public int deleteByKey(String userId, String zone, String layer, String key) { return 0; }

        final List<MemoryRecordEntity> store = new ArrayList<>();
        @Override public void upsert(MemoryRecordEntity entity) { store.add(entity); }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String u, String z, String l) {
            return Collections.emptyList();
        }
        @Override public MemoryRecordEntity queryByKey(String u, String z, String l, String k) {
            for (MemoryRecordEntity e : store) {
                if (u.equals(e.userId) && z.equals(e.zone)
                        && l.equals(e.layer) && k.equals(e.key)) {
                    return e;
                }
            }
            return null;
        }
        @Override public int deleteByUser(String u) { return 0; }
        @Override public int deleteByUserZone(String u, String z) { return 0; }
    }
}
