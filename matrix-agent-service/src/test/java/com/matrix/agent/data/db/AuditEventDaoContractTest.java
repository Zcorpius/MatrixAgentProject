package com.matrix.agent.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * AuditEventDao 新增 {@code deleteByUserZone / queryByUserZone} 契约测试。
 *
 * <p>JVM 路径用 fake in-memory DAO 验证 caller 隔离行为(同 user 不同 zone 不被误删 +
 * 跨 user 不被误删);方法签名 + 返回值类型用反射验证(防 caller 误改成 deleteByUser 或
 * 仅 zone 过滤)。
 *
 * <p>真实 Room SQL 执行(包括 ALTER TABLE userId 列、CREATE INDEX、SQL 解析、参数绑定、
 * transaction 原子性)由 {@code src/androidTest/MatrixDatabaseMigrationTest} +
 * {@code src/androidTest/AuditEventDaoInstrumentedTest} 在 emulator 上验证。
 *
 * <p>覆盖该缓解:Migration 仅 1 ALTER + 1 CREATE INDEX 语句最小化,
 * 失败时 Room 抛 IllegalStateException,AppContainer catch 后退化 NoopAuditRepository。
 */
public final class AuditEventDaoContractTest {

    @Test
    public void auditEventDeleteByUserZoneKeepsOtherZone() {
        FakeAuditEventDao dao = new FakeAuditEventDao();
        dao.insert(entity("req-1", "demo-driver", "DRIVER", "PRE_TOOL", "{ }"));
        dao.insert(entity("req-2", "demo-driver", "PASSENGER", "PRE_TOOL", "{ }"));
        dao.insert(entity("req-3", "demo-driver", "DRIVER", "POST_TOOL", "{ }"));

        int removed = dao.deleteByUserZone("demo-driver", "DRIVER");

        assertEquals("DRIVER zone 2 行被删(PRE + POST)", 2, removed);
        assertEquals("PASSENGER zone 行保留", 1, dao.store.size());
    }

    @Test
    public void auditEventDeleteByUserZoneDoesNotTouchOtherUsers() {
        FakeAuditEventDao dao = new FakeAuditEventDao();
        dao.insert(entity("req-1", "demo-driver", "DRIVER", "PRE_TOOL", "{ }"));
        dao.insert(entity("req-2", "other-user", "DRIVER", "PRE_TOOL", "{ }"));

        int removed = dao.deleteByUserZone("demo-driver", "DRIVER");

        assertEquals("仅 demo-driver 1 行被删", 1, removed);
        assertEquals("other-user 保留", 1, dao.store.size());
    }

    @Test
    public void auditEventQueryByUserZoneReturnsOnlyMatchingRows() {
        FakeAuditEventDao dao = new FakeAuditEventDao();
        dao.insert(entity("req-1", "demo-driver", "DRIVER", "PRE_TOOL", "{ }"));
        dao.insert(entity("req-2", "demo-driver", "DRIVER", "POST_TOOL", "{ }"));
        dao.insert(entity("req-3", "demo-passenger", "PASSENGER", "PRE_TOOL", "{ }"));

        List<AuditEventEntity> result = dao.queryByUserZone("demo-driver", "DRIVER");

        assertEquals("demo-driver/DRIVER 仅 2 行匹配", 2, result.size());
        for (AuditEventEntity e : result) {
            assertEquals("demo-driver", e.userId);
            assertEquals("DRIVER", e.zone);
        }
    }

    /**
     * 反射验证方法存在 + 签名 + 返回类型——防 caller 误改成 deleteByUser 或仅 zone 过滤。
     *
     * <p>SQL 字符串本身的契约(含 {@code userId = :userId AND zone = :zone} 双 WHERE)
     * 由 androidTest AuditEventDaoInstrumentedTest 用真实 Room _Impl.java 验证。
     */
    @Test
    public void auditEventDaoMethodSignatures() throws Exception {
        Method delete = AuditEventDao.class.getMethod(
                "deleteByUserZone", String.class, String.class);
        assertEquals("deleteByUserZone 返回类型必须 int", int.class, delete.getReturnType());

        Method query = AuditEventDao.class.getMethod(
                "queryByUserZone", String.class, String.class);
        assertEquals("queryByUserZone 返回类型必须 List<AuditEventEntity>",
                List.class, query.getReturnType());

        // 兼容契约:已有 queryByRequest 不动
        Method byRequest = AuditEventDao.class.getMethod("queryByRequest", String.class);
        assertEquals("queryByRequest 返回类型必须 List<AuditEventEntity>",
                List.class, byRequest.getReturnType());
    }

    /**
     * AuditEventEntity.userId 字段必须有默认值 ""(Migration ALTER
     * DEFAULT ''),否则历史行插入会 NPE。
     */
    @Test
    public void auditEventEntityUserIdDefaultsToEmptyString() {
        AuditEventEntity entity = new AuditEventEntity();
        assertEquals("userId 字段默认必须空串(Migration ALTER DEFAULT '' 一致)",
                "", entity.userId);
        // history 行 userId='' 不影响 insert(等价旧行为)
        entity.requestId = "req-legacy";
        entity.type = "TERMINAL";
        entity.actor = "DRIVER";
        entity.zone = "DRIVER";
        entity.happenedAtMs = System.currentTimeMillis();
        entity.payloadJson = "{}";
        assertEquals("userId 仍为空串", "", entity.userId);
    }

    private static AuditEventEntity entity(String requestId, String userId, String zone,
            String type, String payloadJson) {
        AuditEventEntity e = new AuditEventEntity();
        e.requestId = requestId;
        e.userId = userId;
        e.zone = zone;
        e.type = type;
        e.actor = zone;
        e.happenedAtMs = System.currentTimeMillis();
        e.payloadJson = payloadJson;
        return e;
    }

    /** JVM in-memory fake——验证 caller 隔离行为(语义对齐真实 Room _Impl.java)。 */
    private static final class FakeAuditEventDao implements AuditEventDao {
        final Map<String, AuditEventEntity> store = new LinkedHashMap<>();
        private long nextId = 1L;

        @Override
        public void insert(AuditEventEntity entity) {
            entity.id = nextId++;
            store.put(String.valueOf(entity.id), entity);
        }

        @Override
        public List<AuditEventEntity> queryByRequest(String requestId) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store.values()) {
                if (requestId.equals(e.requestId)) result.add(e);
            }
            return result;
        }

        @Override
        public List<AuditEventEntity> queryByUserZone(String userId, String zone) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store.values()) {
                if (userId.equals(e.userId) && zone.equals(e.zone)) result.add(e);
            }
            return result;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (String key : new ArrayList<>(store.keySet())) {
                AuditEventEntity e = store.get(key);
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    store.remove(key);
                    removed++;
                }
            }
            return removed;
        }
    }
}
