package com.matrix.agent.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * 3 个 DAO(Trajectory / SessionHistory / MemoryRecord)新增
 * {@code deleteByUserZone(userId, zone)} 契约测试。
 *
 * <p>JVM 路径用 fake in-memory DAO 验证 caller 隔离行为(同 user 不同 zone 不被误删 +
 * 跨 user 不被误删 + 返回值正确);SQL 字符串契约用反射读 {@code @Query} 注解验证。
 *
 * <p>真实 Room SQL 执行(包括 SQL 解析、参数绑定、transaction 原子性)由
 * {@code src/androidTest/DaoDeleteByUserZoneInstrumentedTest} 验证。
 */
public final class DaoDeleteByUserZoneContractTest {

    // ===== MemoryRecordDao =====

    @Test
    public void memoryRecordDeleteByUserZoneKeepsOtherZone() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        dao.upsert(entity("demo-driver", "DRIVER", "preference", "temperature", "24"));
        dao.upsert(entity("demo-driver", "PASSENGER", "preference", "temperature", "22"));
        dao.upsert(entity("demo-driver", "DRIVER", "working", "last_query", "电量"));

        int removed = dao.deleteByUserZone("demo-driver", "DRIVER");

        assertEquals("DRIVER zone 2 行被删(preference + working)", 2, removed);
        assertEquals("PASSENGER zone 偏好保留", 1, dao.store.size());
        MemoryRecordEntity survivor = dao.store.values().iterator().next();
        assertEquals("PASSENGER", survivor.zone);
    }

    @Test
    public void memoryRecordDeleteByUserZoneDoesNotTouchOtherUsers() {
        FakeMemoryRecordDao dao = new FakeMemoryRecordDao();
        dao.upsert(entity("demo-driver", "DRIVER", "preference", "temperature", "24"));
        dao.upsert(entity("other-user", "DRIVER", "preference", "temperature", "20"));

        int removed = dao.deleteByUserZone("demo-driver", "DRIVER");

        assertEquals("仅 demo-driver 1 行被删", 1, removed);
        assertEquals("other-user 保留", 1, dao.store.size());
    }

    // ===== SessionHistoryDao =====

    @Test
    public void sessionHistoryDeleteByUserZoneKeepsOtherZone() {
        FakeSessionHistoryDao dao = new FakeSessionHistoryDao();
        dao.insert(sessionEntity("demo-driver", "DRIVER", "demo-driver#1", 1000L));
        dao.insert(sessionEntity("demo-driver", "PASSENGER", "demo-driver#2", 2000L));

        int removed = dao.deleteByUserZone("demo-driver", "DRIVER");

        assertEquals("DRIVER session 1 行被删", 1, removed);
        assertEquals("PASSENGER session 保留", 1, dao.store.size());
    }

    // ===== TrajectoryDao =====

    @Test
    public void trajectoryDeleteByUserZoneKeepsOtherZone() {
        FakeTrajectoryDao dao = new FakeTrajectoryDao();
        dao.insert(trajectoryEntity("req-1", "demo-driver", "DRIVER", "demo-driver#1"));
        dao.insert(trajectoryEntity("req-2", "demo-driver", "PASSENGER", "demo-driver#2"));
        dao.insert(trajectoryEntity("req-3", "demo-driver", "DRIVER", "demo-driver#3"));

        int removed = dao.deleteByUserZone("demo-driver", "DRIVER");

        assertEquals("DRIVER 2 行被删", 2, removed);
        assertEquals("PASSENGER 保留", 1, dao.store.size());
    }

    // ===== 方法签名 + 返回类型契约(反射) =====
    // 注:Room @Query retention = CLASS(非 RUNTIME),反射读不到 SQL 字符串。
    // SQL 字符串本身的契约(含 "userId = :userId AND zone = :zone" 双 WHERE)
    // 由 androidTest DaoDeleteByUserZoneInstrumentedTest 用真实 Room 验证。
    // JVM 测试只验证方法存在 + 签名 + 返回类型——防 caller 误改成 deleteByUser 或仅 zone 过滤。

    @Test
    public void deleteByUserZoneMethodSignatureOnAllThreeDaos() throws Exception {
        // 三 DAO 都必须有 deleteByUserZone(String, String) 公开方法,返回 int
        Method m = TrajectoryDao.class.getMethod("deleteByUserZone", String.class, String.class);
        assertEquals("TrajectoryDao.deleteByUserZone 返回类型必须 int",
                int.class, m.getReturnType());
        Method m2 = SessionHistoryDao.class.getMethod("deleteByUserZone", String.class, String.class);
        assertEquals("SessionHistoryDao.deleteByUserZone 返回类型必须 int",
                int.class, m2.getReturnType());
        Method m3 = MemoryRecordDao.class.getMethod("deleteByUserZone", String.class, String.class);
        assertEquals("MemoryRecordDao.deleteByUserZone 返回类型必须 int",
                int.class, m3.getReturnType());
    }

    @Test
    public void memoryRecordRetainsLegacyDeleteByUserForCompatibility() throws Exception {
        // 兼容契约:deleteByUser(String) 保留——账号切换 / 用户擦除场景仍用全量清。
        // 不删除该方法,只新增 deleteByUserZone。SQL 字符串契约由 androidTest 验证。
        Method legacy = MemoryRecordDao.class.getMethod("deleteByUser", String.class);
        assertEquals("deleteByUser 兼容签名返回类型必须 int",
                int.class, legacy.getReturnType());
    }

    // ===== Entities =====

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

    private static TrajectoryEntity trajectoryEntity(String requestId, String userId,
            String zone, String sessionId) {
        TrajectoryEntity e = new TrajectoryEntity();
        e.requestId = requestId;
        e.userId = userId;
        e.zone = zone;
        e.sessionId = sessionId;
        e.startedMs = System.currentTimeMillis();
        e.durationMs = 100L;
        e.stopReason = "DONE";
        e.finalState = "SUCCEEDED";
        e.iterationCount = 1;
        e.totalToolCalls = 0;
        e.successToolCalls = 0;
        e.trajectoryJson = "{}";
        return e;
    }

    // ===== Fake DAOs =====

    private static final class FakeMemoryRecordDao implements MemoryRecordDao {
        final Map<String, MemoryRecordEntity> store = new LinkedHashMap<>();

        @Override
        public void upsert(MemoryRecordEntity entity) {
            store.put(entity.userId + "@" + entity.zone + "#" + entity.layer + "/" + entity.key, entity);
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

    private static final class FakeSessionHistoryDao implements SessionHistoryDao {
        final Map<String, SessionHistoryEntity> store = new LinkedHashMap<>();

        @Override
        public void insert(SessionHistoryEntity entity) {
            store.put(entity.userId + "@" + entity.zone + "#" + entity.sessionId, entity);
        }

        @Override
        public List<SessionHistoryEntity> queryByUserZone(String userId, String zone, int limit) {
            List<SessionHistoryEntity> result = new ArrayList<>();
            for (SessionHistoryEntity e : store.values()) {
                if (e.userId.equals(userId) && e.zone.equals(zone)) result.add(e);
            }
            return result;
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

    private static final class FakeTrajectoryDao implements TrajectoryDao {
        final Map<String, TrajectoryEntity> store = new LinkedHashMap<>();

        @Override
        public void insert(TrajectoryEntity entity) {
            store.put(entity.requestId, entity);
        }

        @Override
        public TrajectoryEntity queryByRequestScoped(String userId, String zone, String requestId) {
            TrajectoryEntity e = store.get(requestId);
            if (e == null || !e.userId.equals(userId) || !e.zone.equals(zone)) return null;
            return e;
        }

        @Override
        public List<TrajectoryEntity> queryBySessionScoped(String userId, String zone,
                String sessionId, int limit) {
            List<TrajectoryEntity> result = new ArrayList<>();
            for (TrajectoryEntity e : store.values()) {
                if (e.userId.equals(userId) && e.zone.equals(zone) && e.sessionId.equals(sessionId)) {
                    result.add(e);
                }
            }
            return result;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (String key : new ArrayList<>(store.keySet())) {
                TrajectoryEntity e = store.get(key);
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    store.remove(key);
                    removed++;
                }
            }
            return removed;
        }
    }
}
