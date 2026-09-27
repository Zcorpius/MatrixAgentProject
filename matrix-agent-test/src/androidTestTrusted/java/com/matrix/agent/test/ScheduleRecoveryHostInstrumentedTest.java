package com.matrix.agent.test;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.os.SystemClock;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.client.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.*;

/** Two-phase device fixture permits killing Host or changing device idle state with no bound client. */
@RunWith(AndroidJUnit4.class)
public final class ScheduleRecoveryHostInstrumentedTest {
    @Test public void prepare() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var settings=InstrumentationRegistry.getArguments();
        int count=Integer.parseInt(settings.getString("count","1"));
        long delay=Long.parseLong(settings.getString("delayMillis","30000"));
        assertTrue(count>0 && count<=100);
        var agent=MatrixAgent.create(context,null,15000,null); var manager=agent.getScheduleManager();
        cleanup(manager);
        JSONArray ids=new JSONArray(); long due=System.currentTimeMillis()+delay;
        try {
            for(int i=0;i<count;i++) {
                var spec=new ScheduleSpec("[Matrix验证] recovery "+i,new ScheduleTiming(ONCE,"Asia/Shanghai",due,0,"",0,"","",false,"",0),
                        new ScheduleAction(NOTIFICATION,"批量与冷唤醒验证 "+i,"",0,"{}",List.of(),false,false),600000,WITHIN_GRACE);
                var saved=manager.create(spec,UUID.randomUUID().toString()); assertEquals(saved.message,0,saved.code); ids.put(saved.scheduleId);
                assertTrue(context.getSharedPreferences("schedule-recovery-fixture",0).edit().putString("ids",ids.toString()).putLong("due",due).commit());
            }
            assertEquals(count,ids.length());
        } finally { agent.release(); }
    }
    private static void cleanup(ScheduleManager manager) {
        String cursor="";
        do {
            var page=manager.list(cursor,100); assertEquals(0,page.code);
            for(var plan:page.items) if(plan.spec.title.startsWith("[Matrix验证] recovery "))
                manager.control(plan.scheduleId,"",plan.revision,DELETE,UUID.randomUUID().toString());
            cursor=page.nextCursor;
        } while(!cursor.isEmpty());
    }
    @Test public void verifyAndCleanup() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext(); var preferences=context.getSharedPreferences("schedule-recovery-fixture",0);
        boolean blocked = Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("expectBlocked", "false"));
        JSONArray ids=new JSONArray(preferences.getString("ids","[]")); assertTrue("prepare must run first",ids.length()>0);
        var agent=MatrixAgent.create(context,null,15000,null); var manager=agent.getScheduleManager();
        try {
            long deadline=SystemClock.elapsedRealtime()+60000; boolean complete;
            do {
                complete=true;
                for(int i=0;i<ids.length();i++) {
                    var page=manager.listRuns(ids.getString(i),"",100); assertEquals(0,page.code);
                    if(page.items.size()!=1 || !terminalRun(page.items.get(0).state)) { complete=false; break; }
                }
                if(!complete) SystemClock.sleep(250);
            } while(!complete && SystemClock.elapsedRealtime()<deadline);
            assertTrue("all due candidates must converge",complete);
            long maximumDelay=0;
            for(int i=0;i<ids.length();i++) {
                var page=manager.listRuns(ids.getString(i),"",100); assertEquals(1,page.items.size()); var run=page.items.get(0);
                assertEquals(run.reason, blocked ? FAILED : SUCCEEDED, run.state); assertEquals(run.reason, blocked ? DELIVERY_BLOCKED : DELIVERED, run.deliveryStatus);
                if (blocked) assertEquals("NOTIFICATION_CHANNEL_BLOCKED", run.reason);
                maximumDelay=Math.max(maximumDelay,run.receivedAt-run.scheduledAt);
            }
            android.os.Bundle evidence=new android.os.Bundle(); evidence.putString("scheduleEvidence","count="+ids.length()+", maxReceiveDelayMillis="+maximumDelay);
            InstrumentationRegistry.getInstrumentation().sendStatus(0,evidence);
        } finally {
            for(int i=0;i<ids.length();i++) { var plan=manager.get(ids.getString(i)); manager.control(plan.scheduleId,"",plan.revision,DELETE,UUID.randomUUID().toString()); }
            cleanup(manager); preferences.edit().clear().commit(); agent.release();
        }
    }
}
