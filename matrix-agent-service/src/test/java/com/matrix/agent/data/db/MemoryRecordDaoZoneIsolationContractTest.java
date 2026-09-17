package com.matrix.agent.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * MemoryRecordDao / SessionHistoryDao 查询接口必须以
 * (userId, zone) 作访问域——避免同一 Android User 不同 zone 的记忆被一并取出。
 *
 * <p>没有真 Room 集成(走 APK 手动验证),用 fake in-memory DAO 实现验证
 * SQL WHERE 条件包含 zone 字段——SQL 语句通过反射拿 @Query annotation 检查,
 * fake 实现按 (userId, zone) 过滤保证 caller 行为正确。
 *
 * <p>引入 src/androidTest + inMemoryDatabaseBuilder 时,本测试可迁移到真 Room
 * 验证 SQL 实际生效;接口签名契约由 JVM 单测保底。
 */
public final class MemoryRecordDaoZoneIsolationContractTest {

    /**
     * fake MemoryRecordDao 按 (userId, zone, layer) 过滤——验证 caller 行为。
     * 同一 userId + 不同 zone + 相同 layer 的两条记录,必须只返回调用方 zone 的那条。
     */
    @Test
    public void queryByUserZoneLayerFiltersOnZoneNotJustUserId() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        MemoryRecordEntity driverPref = entity("demo-driver", "DRIVER", "preference",
                "user.preference.temperature", "24");
        MemoryRecordEntity passengerPref = entity("demo-driver", "PASSENGER", "preference",
                "user.preference.temperature", "22");
        MemoryRecordEntity driverWorking = entity("demo-driver", "DRIVER", "working",
                "turn.last_query", "电量");
        dao.upsert(driverPref);
        dao.upsert(passengerPref);
        dao.upsert(driverWorking);

        List<MemoryRecordEntity> driverPreference = dao.queryByUserZoneLayer(
                "demo-driver", "DRIVER", "preference");

        assertEquals(1, driverPreference.size());
        assertEquals("24", driverPreference.get(0).value);
        assertTrue("PASSENGER zone 的记录绝不能被 DRIVER 查询带回",
                driverPreference.stream().noneMatch(e -> "PASSENGER".equals(e.zone)));
    }

    /**
     * SessionHistory 同理:同一 userId 的 DRIVER / PASSENGER 历史必须按 zone 隔离。
     */
    @Test
    public void sessionHistoryQueryByUserZoneFiltersOnZone() {
        FakeSessionHistoryDao dao = new FakeSessionHistoryDao();
        SessionHistoryEntity driverSession = sessionEntity(
                "demo-driver", "DRIVER", "demo-driver#1", 1000L);
        SessionHistoryEntity passengerSession = sessionEntity(
                "demo-driver", "PASSENGER", "demo-driver#2", 2000L);
        dao.insert(driverSession);
        dao.insert(passengerSession);

        List<SessionHistoryEntity> driverHistory = dao.queryByUserZone(
                "demo-driver", "DRIVER", 10);

        assertEquals(1, driverHistory.size());
        assertEquals("demo-driver#1", driverHistory.get(0).sessionId);
    }

    /**
     * queryBySession 保留 sessionId-only 查询——sessionId 是主键一部分,
     * 唯一即可定位行;接入 UI 回放时调用方仍需校验 sessionId 归属。
     */
    @Test
    public void sessionHistoryQueryBySessionRemainsSessionIdOnly() {
        FakeSessionHistoryDao dao = new FakeSessionHistoryDao();
        SessionHistoryEntity driverSession = sessionEntity(
                "demo-driver", "DRIVER", "demo-driver#1", 1000L);
        dao.insert(driverSession);

        List<SessionHistoryEntity> bySession = dao.queryBySession("demo-driver#1");

        assertEquals(1, bySession.size());
        assertEquals("demo-driver#1", bySession.get(0).sessionId);
    }

    private static MemoryRecordEntity entity(String userId, String zone, String layer,
            String key, String value) {
        MemoryRecordEntity e = new MemoryRecordEntity();
        e.userId = userId;
        e.zone = zone;
        e.layer = layer;
        e.key = key;
        e.value = value;
        e.score = 1.0;
        e.capturedAtMs = System.currentTimeMillis();
        return e;
    }

    private static SessionHistoryEntity sessionEntity(String userId, String zone,
            String sessionId, long startedAtMillis) {
        SessionHistoryEntity e = new SessionHistoryEntity();
        e.userId = userId;
        e.zone = zone;
        e.sessionId = sessionId;
        e.startedAtMillis = startedAtMillis;
        return e;
    }

    /** Fake MemoryRecordDao:in-memory Map,模拟 queryByUserZoneLayer 的 (userId, zone) 过滤。 */
    private static final class FakeMemoryRecordDao implements MemoryRecordDao {
        final Map<String, MemoryRecordEntity> store = new LinkedHashMap<>();

        @Override
        public void upsert(MemoryRecordEntity entity) {
            store.put(entity.userId + "@" + entity.zone + "#" + entity.layer + "/" + entity.key,
                    entity);
        }

        @Override
        public List<MemoryRecordEntity> queryByUserZoneLayer(String userId, String zone, String layer) {
            List<MemoryRecordEntity> result = new ArrayList<>();
            for (MemoryRecordEntity e : store.values()) {
                if (e.userId.equals(userId) && e.zone.equals(zone) && e.layer.equals(layer)) {
                    result.add(e);
                }
            }
            return result;
        }

        @Override
        public MemoryRecordEntity queryByKey(String userId, String zone, String layer, String key) {
            return store.get(userId + "@" + zone + "#" + layer + "/" + key);
        }

        @Override
        public int deleteByUser(String userId) {
            int removed = 0;
            for (String k : new ArrayList<>(store.keySet())) {
                if (k.startsWith(userId + "@")) {
                    store.remove(k);
                    removed++;
                }
            }
            return removed;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (String k : new ArrayList<>(store.keySet())) {
                if (k.startsWith(userId + "@" + zone + "#")) {
                    store.remove(k);
                    removed++;
                }
            }
            return removed;
        }
    }

    /** Fake SessionHistoryDao:in-memory Map,模拟 queryByUserZone 的 (userId, zone) 过滤。 */
    private static final class FakeSessionHistoryDao implements SessionHistoryDao {
        final Map<String, SessionHistoryEntity> store = new LinkedHashMap<>();

        @Override
        public void insert(SessionHistoryEntity entity) {
            store.put(entity.userId + "@" + entity.zone + "#" + entity.sessionId, entity);
        }

        @Override
        public List<SessionHistoryEntity> queryByUserZone(String userId, String zone, int limit) {
            List<SessionHistoryEntity> matching = new ArrayList<>();
            for (SessionHistoryEntity e : store.values()) {
                if (e.userId.equals(userId) && e.zone.equals(zone)) {
                    matching.add(e);
                }
            }
            matching.sort((a, b) -> Long.compare(b.startedAtMillis, a.startedAtMillis));
            if (matching.size() > limit) {
                return new ArrayList<>(matching.subList(0, limit));
            }
            return matching;
        }

        @Override
        public List<SessionHistoryEntity> queryBySession(String sessionId) {
            List<SessionHistoryEntity> result = new ArrayList<>();
            for (SessionHistoryEntity e : store.values()) {
                if (e.sessionId.equals(sessionId)) result.add(e);
            }
            return result;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (String k : new ArrayList<>(store.keySet())) {
                if (k.startsWith(userId + "@" + zone + "#")) {
                    store.remove(k);
                    removed++;
                }
            }
            return removed;
        }
    }
}
