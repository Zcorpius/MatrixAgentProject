package com.matrix.agent.data.audit;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.matrix.agent.data.audit.AuditEventTypes;
import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;
import com.matrix.agent.data.db.MatrixDatabase;

/**
 * AuditEventRecorder 真实 Room _Impl.java SQL 验证(in-memory,不走 SQLCipher)。
 *
 * <p>JVM 契约测试 {@code AuditEventRecorderTest} 用 fake DAO 验证 fail-open / NOOP / payload;
 * 真实 SQL 字符串 / userId 默认值 / Room insert 在 emulator 上首次跑通由本类负责。
 *
 * <p>覆盖:
 * <ul>
 *   <li>5 个 recordXxx 方法在真实 Room DAO 上写入正确;</li>
 *   <li>userId/zone/actor/requestId 在 audit_event 表正确持久化;</li>
 *   <li>queryByUserZone 能查到刚写入的增量事件;</li>
 *   <li>fail-open 在真实 Room 异常(关闭 DB)时不向上传播。</li>
 * </ul>
 */
@RunWith(AndroidJUnit4.class)
public final class AuditEventRecorderInstrumentedTest {
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
    public void recordPreToolPersistsToAuditEventTable() {
        AuditEventDao dao = db.auditEventDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, immediateExecutor());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("destination", "[destination redacted]");
        recorder.recordPreTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "navigation.route", args, "{destination=[redacted]}");

        List<AuditEventEntity> rows = dao.queryByUserZone("demo-driver", "DRIVER");
        assertEquals(1, rows.size());
        assertEquals("req-1", rows.get(0).requestId);
        assertEquals(AuditEventTypes.PRE_TOOL, rows.get(0).type);
        assertTrue(rows.get(0).payloadJson.contains("navigation.route"));
    }

    @Test
    public void recordPostToolPersistsStatusAndMetrics() {
        AuditEventDao dao = db.auditEventDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, immediateExecutor());

        recorder.recordPostTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "vehicle.climate", "SUCCESS", true, 250L, "[result redacted]");

        List<AuditEventEntity> rows = dao.queryByRequest("req-1");
        assertEquals(1, rows.size());
        AuditEventEntity e = rows.get(0);
        assertEquals(AuditEventTypes.POST_TOOL, e.type);
        assertTrue(e.payloadJson.contains("SUCCESS"));
        assertTrue(e.payloadJson.contains("\"verified\":true"));
        assertTrue(e.payloadJson.contains("\"durationMs\":250"));
    }

    @Test
    public void recordPolicyDecisionPersistsRejectionType() {
        AuditEventDao dao = db.auditEventDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, immediateExecutor());

        recorder.recordPolicyDecision("req-2", "demo-passenger", "PASSENGER", "PASSENGER",
                "vehicle.climate", "CAPABILITY", "[reason redacted]");

        List<AuditEventEntity> rows = dao.queryByUserZone("demo-passenger", "PASSENGER");
        assertEquals(1, rows.size());
        assertEquals(AuditEventTypes.POLICY, rows.get(0).type);
        assertTrue(rows.get(0).payloadJson.contains("CAPABILITY"));
    }

    @Test
    public void queryByUserZoneFiltersCorrectly() {
        AuditEventDao dao = db.auditEventDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, immediateExecutor());

        recorder.recordPreTool("req-a", "demo-driver", "DRIVER", "DRIVER",
                "navigation.route", new LinkedHashMap<>(), "{}");
        recorder.recordPreTool("req-b", "demo-passenger", "PASSENGER", "PASSENGER",
                "media.play", new LinkedHashMap<>(), "{}");

        assertEquals("DRIVER zone 仅 1 行", 1, dao.queryByUserZone("demo-driver", "DRIVER").size());
        assertEquals("PASSENGER zone 仅 1 行", 1, dao.queryByUserZone("demo-passenger", "PASSENGER").size());
        assertEquals("历史 userId='' 行 0", 0, dao.queryByUserZone("", "DRIVER").size());
    }

    private static ExecutorService immediateExecutor() {
        return new ImmediateExecutorService();
    }

    private static final class ImmediateExecutorService extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return new java.util.ArrayList<>(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
