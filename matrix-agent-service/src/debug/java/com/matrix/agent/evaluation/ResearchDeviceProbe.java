package com.matrix.agent.evaluation;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import android.content.Context;
import android.util.Log;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.host.MatrixAgentApplication;
import com.matrix.agent.host.di.ScheduleCallerIdentity;
import com.matrix.agent.identity.*;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.workflow.*;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Creates exactly one synthetic, explicitly network-authorized Host workflow through the production store. */
public final class ResearchDeviceProbe {
    private ResearchDeviceProbe() { }
    public static void run(Context context,String command) {
        try {
            var app=(MatrixAgentApplication)context; var graph=app.scheduleRuntime();
            Path folder=context.getExternalFilesDir(null).toPath().resolve("verification"); Files.createDirectories(folder);
            Path path=folder.resolve("research-device.json");
            JSONObject report=Files.exists(path)?new JSONObject(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)):new JSONObject();
            if((command.equals("start")||command.equals("start_long")) && !report.has("scheduleId")) {
                String query=command.equals("start_long")?"large language model reasoning evaluation":"retrieval augmented generation";
                var clock=graph.clock().sample();
                var owner=new ScheduleIdentity(android.os.Process.myUid(),0,context.getPackageName(),ScheduleCallerIdentity.signature(context,context.getPackageName()),Actor.DRIVER,VehicleZone.DRIVER);
                var timing=new ScheduleTiming(ONCE,clock.deviceZone().getId(),clock.wall().plusSeconds(5).toEpochMilli(),0,"",0,"","",false,"",0);
                var action=new ScheduleAction(WORKFLOW,"比较 retrieval augmented generation 的定义、证据与局限，给出可核对来源。",ResearchWorkflow.ID,1,
                        new JSONObject().put("query",query).toString(),List.of("web.search"),true,false);
                var spec=new ScheduleSpec("合成验证：多来源研究摘要",timing,action,ResearchWorkflow.DEFAULT_WINDOW_MILLIS,WITHIN_GRACE);
                String operation=UUID.randomUUID().toString();
                var created=graph.call(store->store.create(owner,spec,operation,clock));
                report.put("schemaVersion",1).put("synthetic",true).put("scheduleId",created.scheduleId).put("startedAt",java.time.Instant.now().toString())
                        .put("model",app.getContainer().getModelConfigStore().load().model).put("profile","RESEARCH").put("query",query);
                save(path,report); graph.changed();
            }
            if(!report.has("scheduleId"))throw new IllegalStateException("research probe has not been started");
            String plan=report.getString("scheduleId");
            if(command.equals("cancel")||command.equals("cleanup")) {
                String operation=UUID.randomUUID().toString();
                graph.call(store->{var definition=store.dao().definition(plan);
                    if(definition!=null)store.control(android.os.Process.myUid(),plan,"",definition.revision,command.equals("cleanup")?DELETE:PAUSE_AND_CANCEL,operation,graph.clock().sample());return null;});
                graph.changed();
            }
            if (Set.of("revoke", "expire", "exhaust_models", "exhaust_active").contains(command)) {
                graph.call(store -> {
                    var definition = store.dao().definition(plan);
                    if (definition == null || !report.optBoolean("synthetic", false)
                            || !ResearchWorkflow.ID.equals(ScheduleCodec.spec(definition.specJson).action.templateId)) {
                        throw new IllegalStateException("fault injection requires this probe's synthetic research plan");
                    }
                    var run = store.dao().occurrence(plan, definition.fixedOccurrenceKey);
                    if (run == null || terminalRun(run.state)) throw new IllegalStateException("synthetic run is not active");
                    switch (command) {
                        case "revoke" -> run.authorizationJson = "{}";
                        case "expire" -> run.expiresAt = graph.clock().sample().wall().toEpochMilli() - 1;
                        case "exhaust_models" -> run.modelCalls = ExecutionProfile.RESEARCH.maxIterations();
                        case "exhaust_active" -> run.activeMillis = ExecutionProfile.RESEARCH.maxActiveMillis();
                        default -> throw new IllegalArgumentException("unknown fault");
                    }
                    store.dao().updateRun(run);
                    return null;
                });
                report.put("fault", command).put("faultInjectedAt", java.time.Instant.now().toString());
            }
            if(command.equals("recover")) {var sample=graph.clock().sample();graph.wake("REPAIR",-1,sample.wall().toEpochMilli(),sample.elapsedMillis(),ignored->{});}
            var snapshot=graph.call(store->{
                var definition=store.dao().definition(plan); if(definition==null)return null;
                return store.dao().occurrence(plan,definition.fixedOccurrenceKey);
            });
            if(snapshot!=null) {
                report.put("runId",snapshot.runId).put("state",snapshot.state).put("reason",snapshot.reason).put("result",snapshot.result)
                        .put("activeMillis",snapshot.activeMillis).put("modelCalls",snapshot.modelCalls).put("toolCalls",snapshot.toolCalls)
                        .put("scheduledAt",snapshot.scheduledAt).put("startedAtMillis",snapshot.startedAt).put("completedAtMillis",snapshot.completedAt)
                        .put("expiresAt",snapshot.expiresAt).put("deliveryStatus",snapshot.deliveryStatus).put("deliveryFacts",jsonOrEmpty(snapshot.deliveryFactsJson));
                JSONArray steps=new JSONArray();
                for(var step:graph.call(store->store.dao().steps(snapshot.runId))) steps.put(new JSONObject().put("id",step.stepId).put("state",step.state)
                        .put("attempt",step.attempt).put("activeMillis",step.activeMillis).put("reason",step.reason).put("result",step.result)
                        .put("checkpoint",jsonOrEmpty(step.outputJson)).put("startedAt",step.startedAt).put("completedAt",step.completedAt));
                report.put("steps",steps).put("complete",terminalRun(snapshot.state));
            }
            report.put("lastObservedAt",java.time.Instant.now().toString());save(path,report);
            Log.i("MatrixResearchProbe","command="+command+" state="+(snapshot==null?"WAITING":snapshot.state)+" activeMs="+(snapshot==null?0:snapshot.activeMillis));
        } catch(Exception failed) {Log.e("MatrixResearchProbe","failed cause="+failed.getClass().getSimpleName()+":"+failed.getMessage());}
    }
    private static JSONObject jsonOrEmpty(String value) throws JSONException {
        return value==null||value.isBlank()?new JSONObject():new JSONObject(value);
    }
    private static void save(Path path,JSONObject report)throws Exception {
        Path temp=path.resolveSibling("research-device.tmp");Files.write(temp,report.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
