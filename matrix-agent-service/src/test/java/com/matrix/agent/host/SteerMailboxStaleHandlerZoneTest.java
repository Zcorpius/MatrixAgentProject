package com.matrix.agent.host;
import com.matrix.agent.host.di.*;
import com.matrix.agent.task.steer.*;

import com.matrix.agent.identity.Actor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.matrix.agent.task.steer.Steer;
import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;

/**
 * SteerMailboxStaleHandler zone 映射契约测试。
 *
 * <p>评审发现旧实现 {@code sessionId.toUpperCase()} 得到 "DEMO-DRIVER",
 * 但 Repository.clearAuditSafe 用 "DRIVER"/"PASSENGER"(Actor.name()),
 * audit_event.userId/zone 字段对不上,clearByUserZone 清不掉 stale-steer 行。
 *
 * <p>本测试验证 sessionId → (userId, zone, actor) 显式映射:
 * <ul>
 *   <li>"demo-driver" → ("demo-driver", "DRIVER", "DRIVER");</li>
 *   <li>"demo-passenger" → ("demo-passenger", "PASSENGER", "PASSENGER");</li>
 *   <li>未识别 sessionId → ("", "", "")(recorder 内部 null-safe)。</li>
 * </ul>
 */
public final class SteerMailboxStaleHandlerZoneTest {

    @Test
    public void driverSessionMapsToDriverZone() {
        FakeDao dao = new FakeDao();
        AppContainer.SteerMailboxStaleHandler handler =
                new AppContainer.SteerMailboxStaleHandler(new AuditEventRecorder(dao, immediateExecutor()));

        handler.onStaleSteerDropped("demo-driver", Steer.reprompt("hello"), 1L, 2L);

        assertEquals(1, dao.store.size());
        AuditEventEntity e = dao.store.get(0);
        assertEquals("demo-driver", e.userId);
        assertEquals("DRIVER", e.zone);
        assertEquals("DRIVER", e.actor);
        assertTrue("requestId 占位 stale-drop 前缀", e.requestId.startsWith("stale-drop:demo-driver"));
    }

    @Test
    public void passengerSessionMapsToPassengerZone() {
        FakeDao dao = new FakeDao();
        AppContainer.SteerMailboxStaleHandler handler =
                new AppContainer.SteerMailboxStaleHandler(new AuditEventRecorder(dao, immediateExecutor()));

        handler.onStaleSteerDropped("demo-passenger", Steer.reprompt("hello"), 1L, 2L);

        AuditEventEntity e = dao.store.get(0);
        assertEquals("demo-passenger", e.userId);
        assertEquals("PASSENGER", e.zone);
        assertEquals("PASSENGER", e.actor);
    }

    @Test
    public void unknownSessionWritesEmptyFields() {
        FakeDao dao = new FakeDao();
        AppContainer.SteerMailboxStaleHandler handler =
                new AppContainer.SteerMailboxStaleHandler(new AuditEventRecorder(dao, immediateExecutor()));

        handler.onStaleSteerDropped("session-xyz-999",
                Steer.forceTool("climate.set_temperature", java.util.Collections.<String, Object>emptyMap()),
                1L, 2L);

        AuditEventEntity e = dao.store.get(0);
        assertEquals("未识别 sessionId 写空 userId", "", e.userId);
        assertEquals("未识别 sessionId 写空 zone", "", e.zone);
        assertEquals("未识别 sessionId 写空 actor", "", e.actor);
    }

    @Test
    public void zoneNeverEqualsUppercaseSessionId() {
        FakeDao dao = new FakeDao();
        AppContainer.SteerMailboxStaleHandler handler =
                new AppContainer.SteerMailboxStaleHandler(new AuditEventRecorder(dao, immediateExecutor()));

        handler.onStaleSteerDropped("demo-driver", Steer.reprompt("hi"), 1L, 2L);

        AuditEventEntity e = dao.store.get(0);
        assertTrue("zone 不该是 'DEMO-DRIVER'(评审发现 bug)",
                !"DEMO-DRIVER".equals(e.zone));
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
