package com.matrix.agent.test;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.SystemClock;
import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.client.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;

/** Tests only records carrying the dedicated verification title, through the public signed API. */
@RunWith(AndroidJUnit4.class)
public final class CalendarWorkflowHostInstrumentedTest {
    private MatrixAgent connect() {
        var agent = MatrixAgent.create(InstrumentationRegistry.getInstrumentation().getTargetContext(), null, 15_000, null);
        assertEquals(ConnectionState.CONNECTED, agent.getState()); return agent;
    }
    private static JSONObject invoke(ScheduleManager manager, String capability, JSONObject args) throws JSONException {
        var result=manager.calendar(capability,args.toString(),UUID.randomUUID().toString());
        assertEquals(capability+": "+result.message+" "+result.status,0,result.code); return new JSONObject(result.payloadJson);
    }
    private static JSONObject event(long calendar,long at) throws JSONException {
        return new JSONObject().put("calendarId",calendar).put("title","[Matrix验证] calendar instance")
                .put("startMillis",at).put("endMillis",at+3_600_000).put("timeZone","Asia/Shanghai");
    }
    private static void deleteEvent(ScheduleManager manager,long id) throws JSONException {
        var event=invoke(manager,"calendar.get",new JSONObject().put("eventId",id)).getJSONObject("event");
        invoke(manager,"calendar.delete",new JSONObject().put("eventId",id).put("revision",event.getString("revision")).put("scope","series"));
    }
    private static void deletePlan(ScheduleManager manager,String id) {
        var plan=manager.get(id); var reply=manager.control(id,"",plan.revision,DELETE,UUID.randomUUID().toString()); assertEquals(reply.message,0,reply.code);
    }
    @Test public void calendarCreateReplayConflictUpdateAndDelete() throws Exception {
        var agent=connect(); var manager=agent.getScheduleManager(); long id=0;
        try {
            long calendar=invoke(manager,"calendar.initialize",new JSONObject()).getLong("calendarId");
            JSONObject params=event(calendar,System.currentTimeMillis()+600_000).put("reminderMinutes",10);
            String operation=UUID.randomUUID().toString(); var created=manager.calendar("calendar.create",params.toString(),operation);
            assertEquals(created.message+" "+created.status,0,created.code); id=new JSONObject(created.payloadJson).getLong("eventId");
            assertEquals(created.payloadJson,manager.calendar("calendar.create",params.toString(),operation).payloadJson);
            assertTrue(manager.calendar("calendar.create",new JSONObject(params.toString()).put("title","different").toString(),operation).code!=0);
            var before=invoke(manager,"calendar.get",new JSONObject().put("eventId",id)).getJSONObject("event");
            params.remove("calendarId"); params.put("eventId",id).put("revision",before.getString("revision")).put("scope","series").put("title","[Matrix验证] updated");
            var updated=invoke(manager,"calendar.update",params).getJSONObject("event"); assertEquals("[Matrix验证] updated",updated.getString("title"));
            assertEquals(10,updated.getJSONArray("reminders").getInt(0));
        } finally { if(id>0) deleteEvent(manager,id); agent.release(); }
    }
    @Test public void calendarMoveEarlierRearmsAndDeliversOnce() throws Exception {
        var agent=connect(); var manager=agent.getScheduleManager(); long event=0; String planId=null;
        try {
            long calendar=invoke(manager,"calendar.initialize",new JSONObject()).getLong("calendarId"); long original=System.currentTimeMillis()+180_000;
            var created=invoke(manager,"calendar.create",event(calendar,original)); event=created.getLong("eventId");
            var bound=manager.bindCalendar(event,original,"AGENT",UUID.randomUUID().toString()); assertEquals(bound.message,0,bound.code);
            String binding=new JSONObject(bound.payloadJson).getString("bindingId");
            var timing=new ScheduleTiming(CALENDAR_OFFSET,"Asia/Shanghai",0,0,"",0,"","",false,binding,0);
            var spec=new ScheduleSpec("[Matrix验证] calendar binding",timing,new ScheduleAction(NOTIFICATION,"日历绑定真机验证","",0,"{}",List.of(),false,false),120_000,WITHIN_GRACE);
            var saved=manager.create(spec,UUID.randomUUID().toString()); assertEquals(saved.message,0,saved.code); planId=saved.scheduleId;
            long moved=System.currentTimeMillis()+12_000;
            JSONObject edit=event(calendar,moved); edit.remove("calendarId"); edit.put("eventId",event).put("scope","series")
                    .put("revision",created.getJSONObject("event").getString("revision")); invoke(manager,"calendar.update",edit);
            var run=await(manager,planId,45_000); assertEquals(run.reason,SUCCEEDED,run.state); assertEquals(DELIVERED,run.deliveryStatus);
            assertEquals(moved,run.scheduledAt); assertEquals(1,manager.listRuns(planId,"",100).items.size());
        } finally { if(planId!=null) deletePlan(manager,planId); if(event>0) deleteEvent(manager,event); agent.release(); }
    }
    @Test public void boundedAgendaWorkflowRunsThroughForegroundService() throws Exception {
        var agent=connect(); var manager=agent.getScheduleManager(); String id=null;
        try {
            long due=System.currentTimeMillis()+10_000;
            var spec=new ScheduleSpec("[Matrix验证] workflow",new ScheduleTiming(ONCE,"Asia/Shanghai",due,0,"",0,"","",false,"",0),
                    new ScheduleAction(WORKFLOW,"整理已查询日程","daily_agenda",1,"{\"includeTomorrow\":true}",List.of("calendar.query"),false,false),120_000,WITHIN_GRACE);
            var saved=manager.create(spec,UUID.randomUUID().toString()); assertEquals(saved.message,0,saved.code); id=saved.scheduleId;
            var run=await(manager,id,45_000); assertEquals(run.reason,SUCCEEDED,run.state); assertEquals(DELIVERED,run.deliveryStatus);
            assertEquals("daily_agenda", run.templateId); assertEquals(1, run.templateVersion);
            var steps=manager.getSteps(run.runId); assertEquals(4,steps.size());
            assertFalse(steps.get(0).inputSummary.isEmpty());
            for(var step:steps) assertEquals(step.title+": "+step.reason,SUCCEEDED,step.state);
            assertTrue(run.result.contains("日程"));
        } finally { if(id!=null) deletePlan(manager,id); agent.release(); }
    }

    @Test public void recurringInstanceCanMoveTwiceWithoutDuplicatingException() throws Exception {
        var agent = connect(); var manager = agent.getScheduleManager(); long id = 0;
        try {
            long calendar = invoke(manager, "calendar.initialize", new JSONObject()).getLong("calendarId");
            long original = System.currentTimeMillis() + 3_600_000;
            id = invoke(manager, "calendar.create", event(calendar, original).put("rrule", "FREQ=DAILY;COUNT=3")).getLong("eventId");
            JSONObject identity = new JSONObject().put("eventId", id).put("originalStartMillis", original);
            var first = invoke(manager, "calendar.instance", identity);
            JSONObject edit = event(calendar, original + 60_000); edit.remove("calendarId");
            edit.put("eventId", id).put("originalStartMillis", original).put("scope", "instance")
                    .put("revision", first.getString("sourceRevision"));
            invoke(manager, "calendar.update", edit);
            var moved = invoke(manager, "calendar.instance", identity);
            assertEquals(original + 60_000, moved.getLong("startMillis"));
            edit.put("revision", moved.getString("sourceRevision")).put("startMillis", original + 120_000);
            invoke(manager, "calendar.update", edit);
            var movedAgain = invoke(manager, "calendar.instance", identity);
            assertEquals(original + 120_000, movedAgain.getLong("startMillis"));
            assertEquals(moved.getLong("resolvedEventId"), movedAgain.getLong("resolvedEventId"));
        } finally { if (id > 0) deleteEvent(manager, id); agent.release(); }
    }
    @Test public void scheduledAgentUsesIndependentRequestAndReturnsActualModelResult() throws Exception {
        var agent = connect(); var manager = agent.getScheduleManager(); String id = null;
        try {
            long due = System.currentTimeMillis() + 10_000;
            var spec = new ScheduleSpec("[Matrix验证] scheduled agent", new ScheduleTiming(ONCE, "Asia/Shanghai", due, 0, "", 0, "", "", false, "", 0),
                    new ScheduleAction(AGENT, "仅回复：自动任务隔离验证完成。不调用工具。", "", 0, "{}", List.of(), true, false), 120_000, WITHIN_GRACE);
            var saved = manager.create(spec, UUID.randomUUID().toString()); assertEquals(saved.message, 0, saved.code); id = saved.scheduleId;
            var run = await(manager, id, 80_000);
            assertEquals(run.reason, SUCCEEDED, run.state); assertEquals(DELIVERED, run.deliveryStatus);
            assertTrue(run.result, run.result.contains("验证完成"));
            assertTrue(run.startedAt >= run.admittedAt);
        } finally { if (id != null) deletePlan(manager, id); agent.release(); }
    }
    @Test public void agentAgendaWorkflowReturnsModelSummaryAfterParallelReads() throws Exception {
        var agent = connect(); var manager = agent.getScheduleManager(); String id = null;
        try {
            var spec = new ScheduleSpec("[Matrix验证] agent workflow", new ScheduleTiming(ONCE, "Asia/Shanghai", System.currentTimeMillis() + 10_000, 0, "", 0, "", "", false, "", 0),
                    new ScheduleAction(WORKFLOW, "生成日程简报", "daily_agenda_agent", 1, "{}", List.of("calendar.query"), true, false), 120_000, WITHIN_GRACE);
            var saved = manager.create(spec, UUID.randomUUID().toString()); assertEquals(saved.message, 0, saved.code); id = saved.scheduleId;
            var run = await(manager, id, 80_000); assertEquals(run.reason, SUCCEEDED, run.state); assertEquals(DELIVERED, run.deliveryStatus);
            var steps = manager.getSteps(run.runId); assertEquals(4, steps.size());
            assertEquals("today", steps.get(0).stepId); assertEquals("deliver", steps.get(3).stepId);
            for (var step : steps) assertEquals(step.title + ": " + step.reason, SUCCEEDED, step.state);
            assertFalse(run.result.isBlank());
        } finally { if (id != null) deletePlan(manager, id); agent.release(); }
    }
    @Test public void clockDelegationIsExplicitlyUnverifiedAndRejectsDateParameter() throws Exception {
        var agent = connect();
        try {
            var manager = agent.getScheduleManager();
            var invalid = manager.calendar("clock.set_alarm", "{\"hour\":23,\"minute\":59,\"label\":\"[Matrix验证]\",\"date\":\"2027-01-01\"}", UUID.randomUUID().toString());
            assertNotEquals(0, invalid.code);
            var opened = manager.calendar("clock.open", "{}", UUID.randomUUID().toString());
            assertEquals("EXECUTION_UNKNOWN", opened.status);
            assertTrue(opened.payloadJson, opened.payloadJson.contains("DELEGATED_UNVERIFIED"));
        } finally { agent.release(); }
    }
    private static ScheduleRunInfo await(ScheduleManager manager,String id,long timeout) {
        long deadline=SystemClock.elapsedRealtime()+timeout; ScheduleRunPage page;
        do { SystemClock.sleep(250); page=manager.listRuns(id,"",100); }
        while((page.items.isEmpty() || !terminalRun(page.items.get(0).state)) && SystemClock.elapsedRealtime()<deadline);
        assertEquals(0,page.code); assertEquals("no run before timeout",1,page.items.size()); return page.items.get(0);
    }
}
