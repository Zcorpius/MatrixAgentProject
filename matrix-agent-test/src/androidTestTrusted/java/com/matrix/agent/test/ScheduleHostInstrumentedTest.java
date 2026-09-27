package com.matrix.agent.test;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;

import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.api.common.*;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.client.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;

/** Independent signed client: does not install instrumentation into or clear Host private storage. */
@RunWith(AndroidJUnit4.class)
public final class ScheduleHostInstrumentedTest {
    private ScheduleSpec once(String title, long at) {
        return new ScheduleSpec(title, new ScheduleTiming(ONCE, "Asia/Shanghai", at, 0, "", 0, "", "", false, "", 0),
                new ScheduleAction(NOTIFICATION, "Matrix schedule verification", "", 0, "{}", List.of(), false, false), 120_000, WITHIN_GRACE);
    }
    @Test public void createReplayConflictRevisionPauseResumeAndDelete() {
        MatrixAgent agent = MatrixAgent.create(InstrumentationRegistry.getInstrumentation().getTargetContext(), null, 15_000, null);
        String id = null;
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState()); ScheduleManager manager = agent.getScheduleManager(); assertNotNull(manager);
            ScheduleSpec spec = once("[Matrix验证] lifecycle", System.currentTimeMillis() + 600_000);
            String operation = UUID.randomUUID().toString(); ScheduleMutation created = manager.create(spec, operation);
            assertEquals(created.message, MatrixErrorCode.SUCCESS, created.code); id = created.scheduleId;
            ScheduleMutation replay = manager.create(spec, operation); assertEquals(id, replay.scheduleId); assertEquals(created.sequence, replay.sequence);
            assertEquals(MatrixErrorCode.IDEMPOTENCY_CONFLICT, manager.create(once("changed", spec.timing.atMillis), operation).code);
            ScheduleInfo plan = manager.get(id); assertEquals(ACTIVE, plan.state);
            assertEquals(MatrixErrorCode.INVALID_STATE, manager.control(id, "", plan.revision + 1, PAUSE, UUID.randomUUID().toString()).code);
            ScheduleMutation paused = manager.control(id, "", plan.revision, PAUSE, UUID.randomUUID().toString()); assertEquals(0, paused.code);
            assertEquals(PAUSED, manager.get(id).state);
            ScheduleMutation resumed = manager.control(id, "", paused.revision, RESUME, UUID.randomUUID().toString()); assertEquals(0, resumed.code);
            assertEquals(ACTIVE, manager.get(id).state);
            SchedulePage page = manager.list("", 100); assertEquals(0, page.code); String own = id; assertTrue(page.items.stream().anyMatch(p -> p.scheduleId.equals(own)));
        } finally {
            if (id != null) remove(agent.getScheduleManager(), id); agent.release();
        }
    }
    @Test public void exactReminderIsAdmittedAndDeliveredOnce() throws Exception {
        MatrixAgent agent = MatrixAgent.create(InstrumentationRegistry.getInstrumentation().getTargetContext(), null, 15_000, null);
        String id = null;
        try {
            assertEquals(ConnectionState.CONNECTED, agent.getState()); ScheduleManager manager = agent.getScheduleManager();
            ScheduleReadiness ready = manager.getReadiness(); assertEquals(0, ready.code); assertTrue(ready.userReady); assertTrue(ready.exactAlarmAllowed); assertTrue(ready.notificationsAllowed);
            ScheduleMutation created = manager.create(once("[Matrix验证] exact delivery", System.currentTimeMillis() + 10_000), UUID.randomUUID().toString());
            assertEquals(created.message, 0, created.code); id = created.scheduleId;
            long deadline = SystemClock.elapsedRealtime() + 30_000; ScheduleRunPage page;
            do { SystemClock.sleep(250); page = manager.listRuns(id, "", 100); } while ((page.items.isEmpty() || !terminalRun(page.items.get(0).state)) && SystemClock.elapsedRealtime() < deadline);
            assertEquals(0, page.code); assertEquals(1, page.items.size()); ScheduleRunInfo run = page.items.get(0);
            assertEquals(run.reason, SUCCEEDED, run.state); assertEquals(DELIVERED, run.deliveryStatus);
            var receipt = new org.json.JSONObject(run.deliveryFactsJson).getJSONObject("notification");
            assertEquals("DELIVERED", receipt.getString("status")); assertEquals("UNKNOWN", receipt.getString("soundStatus"));
            assertTrue(receipt.getLong("deliveredAt") > 0);
            assertTrue(run.receivedAt >= run.scheduledAt); assertTrue(run.admittedAt >= run.receivedAt); assertTrue(run.deliveredAt >= run.admittedAt);
            SystemClock.sleep(1000); assertEquals(1, manager.listRuns(id, "", 100).items.size());
        } finally { if (id != null) remove(agent.getScheduleManager(), id); agent.release(); }
    }
    @Test public void largePlansArePagedByParcelBytesWithoutTruncationOrMissingRows() throws Exception {
        var agent = MatrixAgent.create(InstrumentationRegistry.getInstrumentation().getTargetContext(), null, 15000, null);
        var manager = agent.getScheduleManager(); var createdIds = new HashSet<String>();
        String goal = "x".repeat(4096), parameters = new org.json.JSONObject().put("padding", "y".repeat(7000)).toString();
        try {
            long due = System.currentTimeMillis() + 600000;
            for (int i = 0; i < 40; i++) {
                var request = once("[Matrix验证] page " + i, due);
                var spec = new ScheduleSpec(request.title, request.timing,
                        new ScheduleAction(NOTIFICATION, goal, "", 0, parameters, List.of(), false, false), request.graceMillis, request.misfirePolicy);
                var created = manager.create(spec, UUID.randomUUID().toString()); assertEquals(created.message, 0, created.code); createdIds.add(created.scheduleId);
            }
            var found = new HashSet<String>(); String cursor = ""; int pages = 0;
            do {
                var page = manager.list(cursor, 100); assertEquals(0, page.code); pages++;
                android.os.Parcel parcel = android.os.Parcel.obtain();
                try { page.writeToParcel(parcel, 0); assertTrue("page must leave Binder headroom", parcel.dataSize() <= 256 * 1024); }
                finally { parcel.recycle(); }
                for (var plan : page.items) if (createdIds.contains(plan.scheduleId)) {
                    assertTrue("duplicate page row", found.add(plan.scheduleId));
                    assertEquals(goal, plan.spec.action.text); assertEquals(parameters, plan.spec.action.parametersJson);
                }
                cursor = page.nextCursor; assertTrue("cursor must advance", pages <= 40);
            } while (!cursor.isEmpty());
            assertTrue("large plans require multiple byte-bounded pages", pages > 1); assertEquals(createdIds, found);
        } finally { for (String id : createdIds) remove(manager, id); agent.release(); }
    }
    private static void remove(ScheduleManager manager, String id) {
        ScheduleInfo plan = manager.get(id); ScheduleMutation result = manager.control(id, "", plan.revision, DELETE, UUID.randomUUID().toString()); assertEquals(result.message, 0, result.code);
    }
}
