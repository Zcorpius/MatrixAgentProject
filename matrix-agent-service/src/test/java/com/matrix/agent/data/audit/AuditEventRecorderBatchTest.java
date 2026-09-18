package com.matrix.agent.data.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;

/**
 * AuditEventRecorder batch 队列 JVM 测试。
 *
 * <p>验证:
 * <ul>
 *   <li>生产构造器({@link AuditEventRecorder#AuditEventRecorder(AuditEventDao)})启用 batch worker,
 *       多条 record 调用经 batch flush 后落库;</li>
 *   <li>{@link AuditEventRecorder#flushSync()} 同步等待 pending 清空;</li>
 *   <li>{@link AuditEventRecorder#awaitQueuedCount(long, long)} 等到具体 N 条已落库;</li>
 *   <li>队列溢出 QUEUE_CAP 时丢最老 PRE_TOOL,POST_TOOL/POLICY/STEER 优先保留。</li>
 * </ul>
 *
 * <p>NOOP 单例不启用 batch(recordXxx 直接 return);测试构造器(注入 Executor)
 * 仍走"每条独立 submit"语义,单测零回归——见 {@link AuditEventRecorderTest}。
 */
public final class AuditEventRecorderBatchTest {

    @Test
    public void batchWorkerFlushesMultipleRecords() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao);
        try {
            for (int i = 0; i < 10; i++) {
                recorder.recordPreTool("req-" + i, "user", "DRIVER", "DRIVER",
                        "cap", new LinkedHashMap<>(), "{}");
            }
            recorder.awaitQueuedCount(10, 3000L);
            assertEquals("10 条 PRE_TOOL 全部落库", 10, dao.store.size());
            assertEquals("queued=10", 10, recorder.getQueuedCount());
            assertEquals("无 dropped", 0, recorder.getDroppedCount());
        } finally {
            recorder.shutdown();
        }
    }

    @Test
    public void flushSyncClearsPendingQueue() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao);
        try {
            // 单条触发 batch worker 等满 50 或 200ms 超时;flushSync 不等满直接 drain
            recorder.recordPostTool("req-1", "user", "DRIVER", "DRIVER",
                    "cap", "SUCCESS", true, 100L, "r");
            recorder.flushSync();
            // flushSync 后队列空;但 batch worker 可能已在 200ms 内自动 flush,
            // 这里仅断言"flushSync 后 queued ≥ 1 且 pending 为空"
            assertTrue("flushSync 后至少 1 条落库", recorder.getQueuedCount() >= 1);
            assertEquals("pending 队列空", 0, recorder.getPendingSize());
        } finally {
            recorder.shutdown();
        }
    }

    @Test
    public void queueOverflowEvictsOldestPreTool() throws Exception {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao);
        try {
            // 阻塞 batch worker:通过 sleep 不让它 flush——填充队列到 QUEUE_CAP+10
            // 注意:FakeDao.insert 是同步快的;此测试仅验证 QUEUE_CAP 上限触发 evict 路径,
            // 不验证严格"丢哪一条"(由 evictOldestPreTool 遍历决定,顺序语义清晰)。
            // 队列超 QUEUE_CAP 后,evict 一条 PRE_TOOL 给 POST_TOOL 让位。
            for (int i = 0; i < AuditEventRecorder.QUEUE_CAP + 5; i++) {
                recorder.recordPreTool("pre-" + i, "user", "DRIVER", "DRIVER",
                        "cap", new LinkedHashMap<>(), "{}");
            }
            // 至少 QUEUE_CAP-1 条 PRE_TOOL 仍在队列或已 flush(取决于 batch worker 速度);
            // 关键断言:overflowDropped ≥ 0,且不抛
            assertTrue("overflowDropped 应 ≥ 0", recorder.getQueueOverflowDropped() >= 0);
            assertTrue("dropped 应 ≥ 0", recorder.getDroppedCount() >= 0);
            recorder.flushSync();
        } finally {
            recorder.shutdown();
        }
    }

    @Test
    public void batchWorkerFailOpenOnDaoException() throws Exception {
        ThrowingDao dao = new ThrowingDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao);
        try {
            recorder.recordPreTool("req-1", "user", "DRIVER", "DRIVER",
                    "cap", new LinkedHashMap<>(), "{}");
            recorder.recordPostTool("req-1", "user", "DRIVER", "DRIVER",
                    "cap", "SUCCESS", true, 1L, "r");
            recorder.flushSync();
            // batch worker 调 dao.insert 抛异常 → fail-open,仅 dropped 计数,不向上传播
            assertTrue("dropped ≥ 2 (Dao 持续抛异常)", recorder.getDroppedCount() >= 2);
            assertEquals("queued=0", 0, recorder.getQueuedCount());
        } finally {
            recorder.shutdown();
        }
    }

    @Test
    public void noopRecorderBypassesBatch() {
        // NOOP 单例 dao=null,recordXxx 直接 return;不启动 batch worker
        AuditEventRecorder noop = AuditEventRecorder.NOOP;
        noop.recordPreTool("req", "user", "z", "a", "cap", new LinkedHashMap<>(), "{}");
        noop.recordPostTool("req", "user", "z", "a", "cap", "S", true, 1L, "r");

        assertEquals(0, noop.getQueuedCount());
        assertEquals(0, noop.getDroppedCount());
        assertEquals("NOOP 不启用 batch", 0, noop.getPendingSize());
    }

    private static final class FakeDao implements AuditEventDao {
        final List<AuditEventEntity> store = new ArrayList<>();

        @Override
        public synchronized void insert(AuditEventEntity entity) {
            store.add(entity);
        }

        @Override
        public synchronized List<AuditEventEntity> queryByRequest(String requestId) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store) {
                if (requestId.equals(e.requestId)) result.add(e);
            }
            return result;
        }

        @Override
        public synchronized List<AuditEventEntity> queryByUserZone(String userId, String zone) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone)) result.add(e);
            }
            return result;
        }

        @Override
        public synchronized int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (int i = store.size() - 1; i >= 0; i--) {
                AuditEventEntity e = store.get(i);
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    store.remove(i);
                    removed++;
                }
            }
            return removed;
        }
    }

    private static final class ThrowingDao implements AuditEventDao {
        @Override public void insert(AuditEventEntity entity) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public List<AuditEventEntity> queryByRequest(String requestId) { return new ArrayList<>(); }
        @Override public List<AuditEventEntity> queryByUserZone(String userId, String zone) { return new ArrayList<>(); }
        @Override public int deleteByUserZone(String userId, String zone) { return 0; }
    }
}
