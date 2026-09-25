package com.matrix.agent.data.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;

/**
 * AuditEventRecorder advanceEpoch + dropByUserZone 用真实 requestEpoch 契约测试。
 *
 * <p>测试 bug:用 {@code futureEpoch = System.currentTimeMillis() + 60_000L}
 * 当 staleEpoch,绕过了 epoch(版本号 1/2/3)与 happenedAtMs(毫秒时间戳)语义混淆的根因
 * ——生产路径 staleEpoch 来自 MemoryStore epoch(1/2/3),永远 < happenedAtMs(17xxx...),
 * isStale 永远 false,stale gate 失效。
 *
 * <p>修复:entity 加 requestEpoch 字段,isStale 用 requestEpoch 而非 happenedAtMs 比较。
 * 本测试改用真实 epoch 值(1/2/3),advanceEpoch(2) 后 requestEpoch=1 的事件 drop,
 * requestEpoch=2 不 drop,requestEpoch=0(老数据 / 未透传)不参与 gate。
 */
public final class AuditEventRecorderClearRaceTest {

    @Test
    public void advanceEpochDropsSubsequentOldEpochEvents() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, immediateExecutor());

        recorder.advanceEpoch(2L);  // MemoryStore epoch 自增到 2

        // requestEpoch=1 < staleEpoch=2 → drop
        recorder.recordPreTool("req-old", "demo-driver", "DRIVER", "DRIVER",
                "climate.set_temperature", Map.<String, Object>of("temp", 24), null, 1L);

        assertEquals("advanceEpoch(2) 后 requestEpoch=1 事件应 drop", 0, dao.store.size());
        assertTrue("dropped 计数应 +1", recorder.getDroppedCount() >= 1);
    }

    @Test
    public void advanceEpochZeroOrSmallerIsNoop() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, immediateExecutor());

        recorder.advanceEpoch(0L);
        recorder.advanceEpoch(-1L);

        recorder.recordPreTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "climate.set_temperature", null, null, 1L);

        assertEquals("0/负 epoch 不该启用 gate", 1, dao.store.size());
    }

    @Test
    public void requestEpochZeroDoesNotParticipateInGate() {
        // 新行为:requestEpoch=0(老数据 / 未透传)不参与 gate
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, immediateExecutor());

        recorder.advanceEpoch(5L);  // 全局 gate 启用

        // 旧 API(无 epoch 参数)默认 requestEpoch=0 → 不参与 gate
        recorder.recordPreTool("req-legacy", "demo-driver", "DRIVER", "DRIVER",
                "climate.set_temperature", null, null);

        assertEquals("requestEpoch=0 老数据不应被 drop", 1, dao.store.size());
    }

    @Test
    public void dropByUserZoneDrainsPendingForMatchingUserZone() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao);

        recorder.recordPreTool("d-1", "demo-driver", "DRIVER", "DRIVER",
                "cap.a", null, null, 1L);
        recorder.recordPostTool("d-2", "demo-driver", "DRIVER", "DRIVER",
                "cap.a", "EXECUTION_FAILED", false, 100L, "x", 1L);
        recorder.recordPolicyDecision("d-3", "demo-driver", "DRIVER", "DRIVER",
                "cap.a", "PARAMETER", "bad", 1L);
        recorder.recordPreTool("p-1", "demo-passenger", "PASSENGER", "PASSENGER",
                "cap.b", null, null, 1L);

        long before = recorder.getPendingSize();
        assertTrue("pending 至少 4 事件", before >= 4);

        recorder.dropByUserZone("demo-driver", "DRIVER", 2L);

        recorder.flushSync();
        int driverInStore = 0;
        int passengerInStore = 0;
        for (AuditEventEntity e : dao.store) {
            if ("demo-driver".equals(e.userId) && "DRIVER".equals(e.zone)) driverInStore++;
            if ("demo-passenger".equals(e.userId) && "PASSENGER".equals(e.zone)) passengerInStore++;
        }
        assertEquals("dropByUserZone 后 dao 不该有 driver 事件", 0, driverInStore);
        assertTrue("passenger 事件不应被 drop", passengerInStore >= 1);
    }

    @Test
    public void dropByUserZoneOnlyAffectsTargetedZone() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao);

        recorder.recordPreTool("d-1", "demo-driver", "DRIVER", "DRIVER",
                "cap.a", null, null, 1L);
        recorder.recordPreTool("p-1", "demo-passenger", "PASSENGER", "PASSENGER",
                "cap.b", null, null, 1L);

        recorder.dropByUserZone("demo-driver", "DRIVER", 5L);  // per-zone gate epoch=5

        // 再次 record driver(requestEpoch=1 < gate=5)→ drop
        recorder.recordPreTool("d-2-after", "demo-driver", "DRIVER", "DRIVER",
                "cap.a", null, null, 1L);
        // 再次 record passenger(driver gate 不影响)→ 正常入队
        recorder.recordPreTool("p-2-after", "demo-passenger", "PASSENGER", "PASSENGER",
                "cap.b", null, null, 1L);

        recorder.flushSync();
        long driverCount = dao.store.stream()
                .filter(e -> "demo-driver".equals(e.userId)).count();
        long passengerCount = dao.store.stream()
                .filter(e -> "demo-passenger".equals(e.userId)).count();
        assertEquals("driver zone 应被完全 drop(原始 d-1 + 后续 d-2-after)", 0L, driverCount);
        assertTrue("passenger zone 不受影响,应有事件,p=" + passengerCount, passengerCount >= 1);
    }

    @Test
    public void dropByUserZoneWithNullArgsIsSafe() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao);
        recorder.dropByUserZone(null, "DRIVER", 1L);
        recorder.dropByUserZone("demo-driver", null, 1L);
        recorder.dropByUserZone("demo-driver", "DRIVER", -1L);
    }

    @Test
    public void staleEventDroppedAtFlushWhenGateAdvancesBetweenEnqueueAndFlush() {
        // delayed executor:enqueue 时 gate 未开,flush 时 gate 已 advance
        FakeDao dao = new FakeDao();
        final List<Runnable> pendingRunnables = new ArrayList<>();
        ExecutorService delayed = new AbstractExecutorService() {
            @Override public void execute(Runnable command) { pendingRunnables.add(command); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        AuditEventRecorder recorder = new AuditEventRecorder(dao, delayed);

        // record 1 个事件 requestEpoch=1
        recorder.recordPreTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "cap.a", null, null, 1L);
        assertEquals("事件未执行", 0, dao.store.size());

        // 推进 epoch = 2(> requestEpoch=1,后续 isStale 返回 true)
        recorder.advanceEpoch(2L);

        // 执行 Runnable,submitImmediate 应在 insert 前再检查 isStale → drop
        for (Runnable r : pendingRunnables) r.run();

        assertEquals("submit 前 stale gate 应 drop,不该 insert", 0, dao.store.size());
    }

    @Test
    public void currentEpochEventNotDroppedAfterAdvance() {
        // advanceEpoch(2) 后 requestEpoch=2(当前 epoch)不应被 drop
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, immediateExecutor());

        recorder.advanceEpoch(2L);

        recorder.recordPreTool("req-current", "demo-driver", "DRIVER", "DRIVER",
                "cap.a", null, null, 2L);

        // requestEpoch=2 不 < staleEpoch=2 → 不 drop
        assertEquals("requestEpoch == staleEpoch 不应 drop", 1, dao.store.size());
    }

    private static ExecutorService immediateExecutor() {
        return new AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() {}
            @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
    }

    private static final class FakeDao implements AuditEventDao {
        @Override public int deleteByUser(String userId) { return 0; }

        final List<AuditEventEntity> store = new ArrayList<>();

        @Override
        public void insert(AuditEventEntity entity) {
            store.add(entity);
        }

        @Override
        public List<AuditEventEntity> queryByRequest(String requestId) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store) {
                if (requestId.equals(e.requestId)) result.add(e);
            }
            return result;
        }

        @Override
        public List<AuditEventEntity> queryByUserZone(String userId, String zone) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone)) result.add(e);
            }
            return result;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
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
}
