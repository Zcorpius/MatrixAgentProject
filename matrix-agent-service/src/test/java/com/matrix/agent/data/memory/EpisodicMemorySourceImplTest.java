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
import com.matrix.agent.task.identity.VehicleZone;
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
        dao.add("user-d", "driver", "sess-a", 100L, "SUCCESS");
        dao.add("user-d", "driver", "sess-b", 200L, "SUCCESS");
        dao.add("user-d", "driver", "sess-c", 300L, "TIMEOUT");
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "查天气", 5);

        assertEquals("返回 3 条 snippet", 3, result.size());
        // queryByUserZone ORDER BY startedAtMillis DESC → 最新在前
        assertTrue("第 1 条 key 含 sess-c", result.get(0).getKey().contains("sess-c"));
        assertTrue("第 2 条 key 含 sess-b", result.get(1).getKey().contains("sess-b"));
        assertTrue("第 3 条 key 含 sess-a", result.get(2).getKey().contains("sess-a"));
    }

    @Test
    public void maxItemsCapsResultSize() {
        FakeDao dao = new FakeDao();
        for (int i = 0; i < 8; i++) {
            dao.add("user-d", "driver", "sess-" + i, 100L + i, "SUCCESS");
        }
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "q", 3);

        assertEquals("maxItems=3 截断结果", 3, result.size());
    }

    @Test
    public void cacheReusesResultWithinTtl() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "sess-a", 100L, "SUCCESS");
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        // 第 1 次:命中 DB
        source.recallEpisodic(new MemoryScope("user-d", VehicleZone.DRIVER), "q", 5);
        assertEquals("DB 调用 1 次", 1, dao.callCount);

        // 第 2 次:命中 cache,不再调 DB
        source.recallEpisodic(new MemoryScope("user-d", VehicleZone.DRIVER), "q", 5);
        assertEquals("DB 仍只调 1 次(cache 命中)", 1, dao.callCount);

        // invalidateCache 后再调:DB 再次调用
        source.invalidateCache();
        source.recallEpisodic(new MemoryScope("user-d", VehicleZone.DRIVER), "q", 5);
        assertEquals("invalidate 后 DB 再次调用", 2, dao.callCount);
    }

    @Test
    public void cacheIsPerUserZone() {
        FakeDao dao = new FakeDao();
        dao.add("user-d", "driver", "sess-driver", 100L, "SUCCESS");
        dao.add("user-p", "passenger", "sess-passenger", 200L, "SUCCESS");
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        // driver 召回
        List<MemorySnippet> driverResult = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "q", 5);
        // passenger 召回
        List<MemorySnippet> passengerResult = source.recallEpisodic(
                new MemoryScope("user-p", VehicleZone.PASSENGER), "q", 5);

        assertEquals("driver 命中 driver session", 1, driverResult.size());
        assertTrue("key 截短后含 sess-dri", driverResult.get(0).getKey().contains("sess-dri"));
        assertEquals("passenger 命中 passenger session", 1, passengerResult.size());
        assertTrue("key 截短后含 sess-pas", passengerResult.get(0).getKey().contains("sess-pas"));
        assertEquals("DB 调用 2 次(2 个不同 cache key)", 2, dao.callCount);
    }

    @Test
    public void daoExceptionReturnsEmptyFailOpen() {
        ThrowingDao dao = new ThrowingDao();
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "q", 5);

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
        dao.add("user-d", "driver", "abcdef1234567890", 100L, "SUCCESS");
        EpisodicMemorySourceImpl source = new EpisodicMemorySourceImpl(dao, 5);

        List<MemorySnippet> result = source.recallEpisodic(
                new MemoryScope("user-d", VehicleZone.DRIVER), "q", 5);

        assertEquals(1, result.size());
        // substring(0, 8) → "abcdef12"
        assertTrue(result.get(0).getKey().startsWith("session#abcdef12"));
    }

    private static final class FakeDao implements SessionHistoryDao {
        final List<SessionHistoryEntity> store = new ArrayList<>();
        int callCount = 0;

        void add(String userId, String zone, String sessionId, long startedAt, String finalState) {
            SessionHistoryEntity e = new SessionHistoryEntity();
            e.userId = userId;
            e.zone = zone;
            e.sessionId = sessionId;
            e.startedAtMillis = startedAt;
            e.finalState = finalState;
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
        @Override public void insert(SessionHistoryEntity entity) { }
        @Override public List<SessionHistoryEntity> queryByUserZone(String userId, String zone, int limit) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public List<SessionHistoryEntity> queryBySession(String sessionId) { return new ArrayList<>(); }
        @Override public int deleteByUserZone(String userId, String zone) { return 0; }
    }
}
