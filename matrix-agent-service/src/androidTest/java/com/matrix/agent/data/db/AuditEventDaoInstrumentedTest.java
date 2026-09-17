package com.matrix.agent.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * AuditEventDao 真实 Room _Impl.java SQL 验证(in-memory,不走 SQLCipher)。
 *
 * <p>JVM 契约测试 {@code AuditEventDaoContractTest} 用 fake DAO 验证 caller 隔离行为;
 * 真实 SQL 字符串 / 参数绑定 / 索引使用 在 emulator 上首次跑通由本类负责。
 *
 * <p>覆盖新增的:
 * <ul>
 *   <li>{@link AuditEventDao#queryByUserZone(String, String)};</li>
 *   <li>{@link AuditEventDao#deleteByUserZone(String, String)}。</li>
 * </ul>
 *
 * <p>用 {@link Room#inMemoryDatabaseBuilder} 绕过 SQLCipher + AndroidKeyStore,
 * 每个测试方法独立 database instance(互不污染)。
 */
@RunWith(AndroidJUnit4.class)
public final class AuditEventDaoInstrumentedTest {
    private MatrixDatabase db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, MatrixDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        if (db != null) db.close();
    }

    @Test
    public void queryByUserZoneReturnsOnlyMatchingRows() {
        AuditEventDao dao = db.auditEventDao();
        dao.insert(newEntity("req-1", "demo-driver", "DRIVER", "PRE_TOOL"));
        dao.insert(newEntity("req-2", "demo-driver", "DRIVER", "POST_TOOL"));
        dao.insert(newEntity("req-3", "demo-passenger", "PASSENGER", "PRE_TOOL"));
        dao.insert(newEntity("req-4", "other-user", "DRIVER", "PRE_TOOL"));

        List<AuditEventEntity> driverRows = dao.queryByUserZone("demo-driver", "DRIVER");
        assertEquals("demo-driver/DRIVER 仅 2 行匹配", 2, driverRows.size());
        for (AuditEventEntity e : driverRows) {
            assertEquals("demo-driver", e.userId);
            assertEquals("DRIVER", e.zone);
        }

        List<AuditEventEntity> passengerRows = dao.queryByUserZone("demo-passenger", "PASSENGER");
        assertEquals("demo-passenger/PASSENGER 1 行", 1, passengerRows.size());

        // 历史格式行 userId='' —— 主路径查询过滤不到
        AuditEventEntity legacy = new AuditEventEntity();
        legacy.requestId = "req-legacy";
        legacy.userId = "";  // 模拟历史格式行
        legacy.type = "TERMINAL";
        legacy.actor = "DRIVER";
        legacy.zone = "DRIVER";
        legacy.happenedAtMs = System.currentTimeMillis();
        legacy.payloadJson = "{}";
        dao.insert(legacy);

        List<AuditEventEntity> emptyUserRows = dao.queryByUserZone("", "DRIVER");
        assertEquals("userId='' 历史行也应能查到(空串匹配)", 1, emptyUserRows.size());
    }

    @Test
    public void deleteByUserZoneIsolatesUserAndZone() {
        AuditEventDao dao = db.auditEventDao();
        dao.insert(newEntity("req-1", "demo-driver", "DRIVER", "PRE_TOOL"));
        dao.insert(newEntity("req-2", "demo-driver", "DRIVER", "POST_TOOL"));
        dao.insert(newEntity("req-3", "demo-driver", "PASSENGER", "PRE_TOOL"));
        dao.insert(newEntity("req-4", "demo-passenger", "PASSENGER", "PRE_TOOL"));
        dao.insert(newEntity("req-5", "other-user", "DRIVER", "PRE_TOOL"));

        int deleted = dao.deleteByUserZone("demo-driver", "DRIVER");
        assertEquals("删 2 行 demo-driver/DRIVER", 2, deleted);

        // demo-driver/PASSENGER 保留(同 user 异 zone)
        assertEquals("demo-driver/PASSENGER 不应被删",
                1, dao.queryByUserZone("demo-driver", "PASSENGER").size());
        // demo-passenger/PASSENGER 保留(异 user)
        assertEquals("demo-passenger/PASSENGER 不应被删",
                1, dao.queryByUserZone("demo-passenger", "PASSENGER").size());
        // other-user/DRIVER 保留(异 user 同 zone)
        assertEquals("other-user/DRIVER 不应被删",
                1, dao.queryByUserZone("other-user", "DRIVER").size());
        // demo-driver/DRIVER 已清空
        assertTrue("demo-driver/DRIVER 应清空",
                dao.queryByUserZone("demo-driver", "DRIVER").isEmpty());
    }

    @Test
    public void queryByRequestStillWorksAfterV2Migration() {
        // 兼容契约:旧版 queryByRequest(String) 不动,新版本仍可用
        AuditEventDao dao = db.auditEventDao();
        dao.insert(newEntity("req-shared", "demo-driver", "DRIVER", "PRE_TOOL"));
        dao.insert(newEntity("req-shared", "demo-passenger", "PASSENGER", "PRE_TOOL"));

        List<AuditEventEntity> byRequest = dao.queryByRequest("req-shared");
        assertEquals("queryByRequest 跨 user/zone 返回所有同 requestId 行",
                2, byRequest.size());
    }

    private static AuditEventEntity newEntity(String requestId, String userId, String zone,
            String type) {
        AuditEventEntity e = new AuditEventEntity();
        e.requestId = requestId;
        e.userId = userId;
        e.zone = zone;
        e.type = type;
        e.actor = zone;
        e.happenedAtMs = System.currentTimeMillis();
        e.payloadJson = "{\"tool\":\"fake.tool\"}";
        return e;
    }
}
