package com.matrix.agent.test;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.client.*;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ScheduleDeliveryHostTest {
    @Test public void runParcelReadsOldLayoutAndPreservesNewChannelFacts() {
        for (int version : new int[]{10, 11, 12}) {
            var run = new ScheduleRunInfo(version, "run", "plan", "title", "occurrence", PARTIAL,
                    DELIVERY_PARTIAL, 1, 2, 3, 4, 5, 0, "summary", "SUPPRESSED_BY_POLICY", "request", 12,
                    "{\"speech\":{\"status\":\"SUPPRESSED_BY_POLICY\"}}", "daily_agenda", 1);
            Parcel parcel = Parcel.obtain();
            try {
                run.writeToParcel(parcel, 0); parcel.writeInt(12345); parcel.setDataPosition(0);
                var restored = ScheduleRunInfo.CREATOR.createFromParcel(parcel);
                assertEquals(version == 10 ? "{}" : run.deliveryFactsJson, restored.deliveryFactsJson);
                assertEquals(version >= 12 ? "daily_agenda" : "", restored.templateId);
                assertEquals(version >= 12 ? 1 : 0, restored.templateVersion);
                assertEquals(12345, parcel.readInt()); assertEquals(0, parcel.dataAvail());
            } finally { parcel.recycle(); }
        }
    }
    @Test public void stepParcelAppendsSafeInputWithoutConsumingAdjacentFields() {
        for (int version : new int[]{11, 12}) {
            var step = new ScheduleStepInfo(version, "run", "today", "今天日程", List.of(), true, SUCCEEDED, 2,
                    1, 2, "ok", "", "查询已授权日历", 500);
            Parcel parcel = Parcel.obtain();
            try {
                step.writeToParcel(parcel, 0); parcel.writeInt(12345); parcel.setDataPosition(0);
                var restored = ScheduleStepInfo.CREATOR.createFromParcel(parcel);
                assertEquals(version >= 12 ? step.inputSummary : "", restored.inputSummary);
                assertEquals(version >= 12 ? 500 : 0, restored.activeMillis);
                assertEquals(12345, parcel.readInt()); assertEquals(0, parcel.dataAvail());
            } finally { parcel.recycle(); }
        }
    }
    @Test public void dndSuppressedSpeechIsPartialWithSeparateNotificationReceipt() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation(); var context = instrumentation.getTargetContext();
        int previous = Settings.Global.getInt(context.getContentResolver(), "zen_mode", 0);
        var agent = MatrixAgent.create(context, null, 15000, null); var manager = agent.getScheduleManager(); String id = null;
        try {
            shell("cmd notification set_dnd none");
            assertEquals(2, Settings.Global.getInt(context.getContentResolver(), "zen_mode", 0));
            var spec = new ScheduleSpec("[Matrix验证] partial delivery", new ScheduleTiming(ONCE, "Asia/Shanghai", System.currentTimeMillis() + 10000,
                    0, "", 0, "", "", false, "", 0), new ScheduleAction(WORKFLOW, "日程简报", "daily_agenda", 1,
                    "{\"includeTomorrow\":false}", List.of("calendar.query"), false, true), 120000, WITHIN_GRACE);
            var created = manager.create(spec, UUID.randomUUID().toString()); assertEquals(created.message, 0, created.code); id = created.scheduleId;
            long deadline = SystemClock.elapsedRealtime() + 40000; ScheduleRunPage page;
            do { SystemClock.sleep(200); page = manager.listRuns(id, "", 100); }
            while ((page.items.isEmpty() || !terminalRun(page.items.get(0).state)) && SystemClock.elapsedRealtime() < deadline);
            assertEquals(1, page.items.size()); var run = page.items.get(0);
            assertEquals(run.reason, PARTIAL, run.state); assertEquals(DELIVERY_PARTIAL, run.deliveryStatus); assertEquals(0, run.deliveredAt);
            var facts = new JSONObject(run.deliveryFactsJson);
            var notification = facts.getJSONObject("notification");
            assertEquals("DELIVERED", notification.getString("status")); assertTrue(notification.getLong("deliveredAt") > 0);
            assertEquals(3, notification.getJSONObject("policy").getInt("interruptionFilter"));
            assertEquals("SUPPRESSED_BY_POLICY", facts.getJSONObject("speech").getString("status"));
            assertFalse(facts.getJSONObject("speech").has("deliveredAt"));
            var steps = manager.getSteps(run.runId);
            assertEquals(SKIPPED, steps.stream().filter(step -> step.stepId.equals("tomorrow")).findFirst().orElseThrow().state);
            assertEquals(PARTIAL, steps.stream().filter(step -> step.stepId.equals("deliver")).findFirst().orElseThrow().state);
        } finally {
            shell("cmd notification set_dnd " + switch (previous) { case 1 -> "priority"; case 2 -> "none"; case 3 -> "alarms"; default -> "all"; });
            if (id != null) { var plan = manager.get(id); manager.control(id, "", plan.revision, DELETE, UUID.randomUUID().toString()); }
            agent.release();
        }
    }
    private static void shell(String command) throws Exception {
        try (var input = new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))) {
            byte[] buffer = new byte[1024]; while (input.read(buffer) >= 0) { /* Drain command completion. */ }
        }
    }
}
