package com.matrix.agent.test;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.client.*;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Settings are changed externally between phases; all owned fixtures are explicitly recorded. */
@RunWith(AndroidJUnit4.class)
public final class ScheduleNotificationPolicyHostTest {
    private android.content.Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private android.content.SharedPreferences fixtures() { return context().getSharedPreferences("schedule-policy-fixture", 0); }
    private MatrixAgent connect() { return MatrixAgent.create(context(), null, 15000, null); }
    private ScheduleSpec spec(long at) {
        return new ScheduleSpec("[Matrix验证] notification policy", new ScheduleTiming(ONCE, "Asia/Shanghai", at, 0, "", 0, "", "", false, "", 0),
                new ScheduleAction(NOTIFICATION, "权限恢复后的一次通知", "", 0, "{}", List.of(), false, false), 600000, WITHIN_GRACE);
    }
    @Test public void prepareActive() {
        var agent = connect();
        try {
            var manager = agent.getScheduleManager(); assertTrue(manager.getReadiness().notificationsAllowed);
            var created = manager.create(spec(System.currentTimeMillis() + 300000), UUID.randomUUID().toString());
            assertEquals(created.message, 0, created.code);
            assertTrue(fixtures().edit().putString("active", created.scheduleId).commit());
            assertEquals(ACTIVE, manager.get(created.scheduleId).state);
        } finally { agent.release(); }
    }
    @Test public void verifyRevokedAndCreateBlockedDraft() {
        var agent = connect();
        try {
            var manager = agent.getScheduleManager(); assertFalse(manager.getReadiness().notificationsAllowed);
            String active = fixtures().getString("active", ""); assertFalse(active.isEmpty());
            long deadline = SystemClock.elapsedRealtime() + 10000;
            ScheduleInfo plan;
            do { plan = manager.get(active); if (plan.health == BLOCKED) break; SystemClock.sleep(100); }
            while (SystemClock.elapsedRealtime() < deadline);
            assertEquals(ACTIVE, plan.state); assertEquals(plan.reason, BLOCKED, plan.health);
            assertTrue(plan.reason.startsWith("NOTIFICATION")); assertTrue(manager.listRuns(active, "", 100).items.isEmpty());
            String operation = UUID.randomUUID().toString(); var request = spec(System.currentTimeMillis() + 300000);
            var created = manager.create(request, operation); assertEquals(created.message, 0, created.code);
            assertTrue(fixtures().edit().putString("draft", created.scheduleId).commit());
            assertEquals(created.scheduleId, manager.create(request, operation).scheduleId);
            var draft = manager.get(created.scheduleId); assertEquals(DRAFT, draft.state); assertEquals(BLOCKED, draft.health);
            assertEquals(MatrixErrorCode.PERMISSION_DENIED, manager.control(draft.scheduleId, "", draft.revision, RESUME, UUID.randomUUID().toString()).code);
        } finally { agent.release(); }
    }
    @Test public void verifyRestoredThenActivateAndDeliver() {
        var agent = connect(); var manager = agent.getScheduleManager();
        String active = fixtures().getString("active", ""), draft = fixtures().getString("draft", "");
        try {
            assertTrue(manager.getReadiness().notificationsAllowed); assertFalse(active.isEmpty()); assertFalse(draft.isEmpty());
            var savedDraft = manager.get(draft); assertEquals(DRAFT, savedDraft.state);
            assertEquals(0, manager.control(draft, "", savedDraft.revision, RESUME, UUID.randomUUID().toString()).code);
            long due = System.currentTimeMillis() + 10000;
            for (String id : List.of(active, draft)) {
                var plan = manager.get(id); var updated = manager.update(id, plan.revision, spec(due), UUID.randomUUID().toString());
                assertEquals(updated.message, 0, updated.code);
            }
            long deadline = SystemClock.elapsedRealtime() + 40000;
            for (String id : List.of(active, draft)) {
                ScheduleRunPage page;
                do { page = manager.listRuns(id, "", 100); if (page.items.size() == 1 && terminalRun(page.items.get(0).state)) break; SystemClock.sleep(200); }
                while (SystemClock.elapsedRealtime() < deadline);
                assertEquals(1, page.items.size()); var run = page.items.get(0);
                assertEquals(run.reason, SUCCEEDED, run.state); assertEquals(DELIVERED, run.deliveryStatus);
            }
        } finally {
            for (String id : List.of(active, draft)) if (!id.isEmpty()) {
                var plan = manager.get(id); manager.control(id, "", plan.revision, DELETE, UUID.randomUUID().toString());
            }
            fixtures().edit().clear().commit(); agent.release();
        }
    }
}
