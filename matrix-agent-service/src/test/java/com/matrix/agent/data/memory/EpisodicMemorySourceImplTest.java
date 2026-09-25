package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

/**
 * EpisodicMemorySourceImpl JVM 测试——用 FakeDao 验证召回 + LRU 缓存 + fail-open。
 *
 * <p>真实 Room _Impl.java SQL 验证由 androidTest 负责。
 */
public final class EpisodicMemorySourceImplTest {

    @Test
    public void recallReturnsRecentSessionsInOrder() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "sess-climate", 100L, "SUCCEEDED");
        dao.add("user-d", "driver", "sess-nav", 200L, "SUCCEEDED");
        dao.add("user-d", "driver", "sess-media", 300L, "TIMEOUT");
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "上次做过什么", 5);

        assertEquals("只返回安全且终态可用的类别", 2, result.size());
        assertEquals("recent_task.navigation.succeeded", result.get(0).getKey());
        assertEquals("recent_task.climate.succeeded", result.get(1).getKey());
    }

    @Test
    public void maxItemsCapsResultSize() {
        FakeDao dao = new FakeDao();
        for (int i = 0; i < 8; i++) {
            dao.add("user-d", "driver", "sess-" + i, 100L + i, "SUCCEEDED");
        }
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "上次做过什么", 3);

        assertEquals("重复类别去重", 1, result.size());
    }

    @Test
    public void noCacheMakesClearImmediatelyVisible() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "sess-climate", 100L, "SUCCEEDED");
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        // 第 1 次:命中 DB
        source.recallEpisodic(new MemoryScope("user-d", VehicleZone.DRIVER), "上次做过什么", 5);
        assertEquals("DB 调用 1 次", 1, dao.callCount);

        dao.store.clear();
        assertTrue(source.recallEpisodic(new MemoryScope("user-d", VehicleZone.DRIVER),
                "上次做过什么", 5).isEmpty());
        assertEquals(2, dao.callCount);
    }

    @Test
    public void cacheIsPerUserZone() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "sess-climate", 100L, "SUCCEEDED");
        dao.add("user-p", "passenger", "sess-nav", 200L, "SUCCEEDED");
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        // driver 召回
        List<MemorySnippet> driverResult = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "上次做过什么", 5);
        // passenger 召回
        List<MemorySnippet> passengerResult = source.recallEpisodic(
                new MemoryScope("user-p", VehicleZone.PASSENGER), "上次做过什么", 5);

        assertEquals("driver 命中 driver session", 1, driverResult.size());
        assertTrue(driverResult.get(0).getKey().contains("climate"));
        assertEquals("passenger 命中 passenger session", 1, passengerResult.size());
        assertTrue(passengerResult.get(0).getKey().contains("navigation"));
        assertEquals("DB 调用 2 次(2 个不同 cache key)", 2, dao.callCount);
    }

    @Test
    public void daoExceptionReturnsEmptyFailOpen() {
        ThrowingDao dao = new ThrowingDao();
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "上次做过什么", 5);

        // 不抛——fail-open 返回空 list
        assertTrue("Dao 异常返回空 list", result.isEmpty());
    }

    @Test
    public void emptyDaoReturnsEmpty() {
        FakeDao dao = new FakeDao();
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "q", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    public void snippetKeyUsesShortSessionId() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "abcdef1234567890", 100L, "SUCCEEDED");
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "上次做过什么", 5);

        assertEquals(1, result.size());
        assertEquals("recent_task.climate.succeeded", result.get(0).getKey());
        assertTrue(!result.get(0).getKey().contains("abcdef"));
    }

    @Test
    public void multiCapabilityEventCanBeFoundByEitherCategory() {
        FakeDao dao = new FakeDao();
        SessionHistoryEntity row = new SessionHistoryEntity();
        row.userId = "user-d";
        row.zone = "driver";
        row.sessionId = "multi";
        row.startedAtMillis = System.currentTimeMillis();
        row.finalState = "SUCCEEDED";
        row.trajectoryJson = "{\"eventSchemaVersion\":2,\"eventId\":\"0123456789abcdef0123456789abcdef\","
                + "\"eventKind\":\"display\",\"successfulCapabilities\":["
                + "\"system.display.set_brightness\",\"system.media.set_volume\"],"
                + "\"verifiedFacts\":[]}";
        dao.store.add(row);
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao);
        MemoryScope scope = new MemoryScope("user-d", VehicleZone.DRIVER);

        assertTrue(source.recallEpisodic(scope, "上次音量多少", 2).get(0).getKey()
                .startsWith("recent.media.succeeded."));
        assertTrue(source.recallEpisodic(scope, "上次屏幕亮度多少", 2).get(0).getKey()
                .startsWith("recent.display.succeeded."));
    }

    private static final class FakeDao implements SessionHistoryDao {
        @Override public int deleteSanitizedLegacyRows() { return 0; }
        @Override public int deleteExact(String userId, String zone, String sessionId, long startedAtMillis) { return 0; }
        @Override public int deleteByUser(String userId) { return 0; }
        @Override public int deleteOlderThan(String userId, String zone, long cutoff) { return 0; }
        @Override public int retainLatest(String userId, String zone, int keep) { return 0; }

        final List<SessionHistoryEntity> store = new ArrayList<>();
        int callCount = 0;

        void add(String userId, String zone, String sessionId, long startedAt, String finalState) {
            SessionHistoryEntity e = new SessionHistoryEntity();
            e.userId = userId;
            e.zone = zone;
            e.sessionId = sessionId;
            e.startedAtMillis = startedAt;
            e.finalState = finalState;
            String capability = sessionId.contains("nav") ? "navigation.start_route"
                    : sessionId.contains("media") ? "media.play"
                    : "vehicle.climate.set_temperature";
            e.trajectoryJson = "{\"successfulCapabilities\":[\"" + capability + "\"]}";
            store.add(e);
        }

        @Override
        public void insert(SessionHistoryEntity entity) {
            store.add(entity);
        }

        @Override
        public List<SessionHistoryEntity> queryByUserZone(String userId, String zone, int limit) {
            callCount++;
            List<SessionHistoryEntity> result = new ArrayList<>();
            for (SessionHistoryEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    result.add(e);
                }
            }
            // DESC by startedAtMillis
            Collections.sort(result, (a, b) -> Long.compare(b.startedAtMillis, a.startedAtMillis));
            if (result.size() > limit) {
                return new ArrayList<>(result.subList(0, limit));
            }
            return result;
        }

        @Override
        public List<SessionHistoryEntity> queryBySession(String sessionId) {
            List<SessionHistoryEntity> result = new ArrayList<>();
            for (SessionHistoryEntity e : store) {
                if (sessionId.equals(e.sessionId)) result.add(e);
            }
            return result;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (int i = store.size() - 1; i >= 0; i--) {
                SessionHistoryEntity e = store.get(i);
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    store.remove(i);
                    removed++;
                }
            }
            return removed;
        }
    }

    private static final class ThrowingDao implements SessionHistoryDao {
        @Override public int deleteSanitizedLegacyRows() { return 0; }
        @Override public int deleteExact(String userId, String zone, String sessionId, long startedAtMillis) { return 0; }
        @Override public int deleteByUser(String userId) { return 0; }
        @Override public int deleteOlderThan(String userId, String zone, long cutoff) { return 0; }
        @Override public int retainLatest(String userId, String zone, int keep) { return 0; }

        @Override public void insert(SessionHistoryEntity entity) { }
        @Override public List<SessionHistoryEntity> queryByUserZone(String userId, String zone, int limit) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public List<SessionHistoryEntity> queryBySession(String sessionId) { return new ArrayList<>(); }
        @Override public int deleteByUserZone(String userId, String zone) { return 0; }
    }
}
