package com.matrix.agent.schedule.store;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.workflow.*;
import org.json.*;
import org.junit.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class ResearchRecoveryTest {
    private final ScheduleStoreFixture db=new ScheduleStoreFixture();
    private final AtomicLong epoch=new AtomicLong(1);
    private final ScheduleStore store=new ScheduleStore(db,epoch::get,()->false);
    private final WorkflowStore workflow=new WorkflowStore(store);
    private final WorkflowTemplate template=ResearchWorkflow.template();
    private final Instant origin=Instant.parse("2026-09-27T08:00:00Z");
    private ClockSample clock(long offset){return new ClockSample(origin.plusMillis(offset),1000+offset,"boot",ZoneId.of("UTC"));}
    private String initialize(){
        var spec=new ScheduleSpec("synthetic research",new ScheduleTiming(ONCE,"UTC",origin.plusSeconds(1).toEpochMilli(),0,"",0,"","",false,"",0),
                new ScheduleAction(WORKFLOW,"research",ResearchWorkflow.ID,1,"{\"query\":\"synthetic\"}",List.of("web.search"),true,false),2_400_000,WITHIN_GRACE);
        var identity=new ScheduleIdentity(10001,0,"synthetic.owner","signature",Actor.DRIVER,VehicleZone.DRIVER);
        String plan=store.create(identity,spec,UUID.randomUUID().toString(),clock(0)).scheduleId;
        var admission=new ScheduleAdmissionStore(store);admission.reconcile(clock(1000),false,true);
        admission.admitOne("admit:"+plan,clock(1000),clock(1000).wall().toEpochMilli(),clock(1000).elapsedMillis());
        var run=store.dao().occurrence(plan,store.dao().definition(plan).fixedOccurrenceKey);run.state=RUNNING;store.dao().updateRun(run);workflow.initialize(run,template,clock(1000));return run.runId;
    }
    private void completeSource(String runId,String source)throws Exception {
        var step=store.dao().step(runId,source);
        if(step.state!=QUEUED)workflow.ready(store.dao().run(runId),template,clock(1000));step=store.dao().step(runId,source);
        assertTrue(workflow.begin(runId,source,step.leaseGeneration,template,clock(1000))>0);
        var data=new JSONObject().put("version",1).put("source",source).put("sources",new JSONArray()).put("count",0)
                .put("coverage","SNIPPETS_AND_METADATA_ONLY").put("queryDigest",ScheduleCodec.digest("synthetic")).put("retrievedAt",clock(2000).wall().toEpochMilli());
        workflow.finish(runId,source,step.leaseGeneration,SUCCEEDED,"read complete",data.toString(),"",false,template,clock(2000));
    }
    @Test public void recoveryReusesFreshReadCheckpointAndRetainsModelLedger()throws Exception {
        String id=initialize();completeSource(id,"wikipedia");
        for(int i=0;i<39;i++)assertTrue(workflow.reserveModelCall(id,template));
        String checkpoint=store.dao().step(id,"wikipedia").outputJson;
        workflow.recover(store.dao().run(id),template,clock(3000));
        assertEquals(SUCCEEDED,store.dao().step(id,"wikipedia").state);assertEquals(checkpoint,store.dao().step(id,"wikipedia").outputJson);
        assertTrue(new WorkflowStore(store).reserveModelCall(id,template));assertFalse(new WorkflowStore(store).reserveModelCall(id,template));
        assertEquals(40,store.dao().run(id).modelCalls);
    }
    @Test public void invalidCheckpointInvalidatesDerivedReadsButNeverReplaysUncertainDelivery()throws Exception {
        String id=initialize();completeSource(id,"wikipedia");
        var wiki=store.dao().step(id,"wikipedia");wiki.outputJson=new JSONObject(wiki.outputJson).put("runId","other-run").toString();store.dao().updateStep(wiki);
        for(String name:List.of("review_wikipedia","summary")){var step=store.dao().step(id,name);step.state=SUCCEEDED;step.attempt=1;step.result="stale analysis";store.dao().updateStep(step);}
        var delivery=store.dao().step(id,"deliver");delivery.state=RUNNING;delivery.attempt=1;store.dao().updateStep(delivery);
        workflow.recover(store.dao().run(id),template,clock(3000));
        for(String name:List.of("wikipedia","review_wikipedia","summary")){var step=store.dao().step(id,name);assertEquals(name,WAITING_DEPENDENCY,step.state);assertEquals("",step.result);}
        assertEquals(EXECUTION_UNKNOWN,store.dao().step(id,"deliver").state);
    }
    @Test public void researchExceedsOldBudgetButStopsAtParentCeilingExpiryCancellationAndEpoch() {
        String id=initialize();var run=store.dao().run(id);run.activeMillis=121_000;store.dao().updateRun(run);
        workflow.ready(run,template,clock(1000));var wiki=store.dao().step(id,"wikipedia");
        assertEquals(20_000,workflow.begin(id,"wikipedia",wiki.leaseGeneration,template,clock(1000)));
        workflow.finish(id,"wikipedia",wiki.leaseGeneration,FAILED,"","{}","NETWORK",true,template,clock(2000));
        run=store.dao().run(id);run.activeMillis=1_800_000;run.budgetAnchorElapsed=null;store.dao().updateRun(run);
        var crossref=store.dao().step(id,"crossref");assertEquals(0,workflow.begin(id,"crossref",crossref.leaseGeneration,template,clock(3000)));
        run=store.dao().run(id);run.state=CANCEL_REQUESTED;store.dao().updateRun(run);assertFalse(workflow.reserveModelCall(id,template));
        workflow.stopWaiting(run,"CANCELLED",clock(4000));assertEquals(CANCELLED,store.dao().step(id,"summary").state);
        run.state=RUNNING;store.dao().updateRun(run);epoch.incrementAndGet();assertFalse(workflow.reserveModelCall(id,template));
    }
    @Test public void callerCannotSelectAWindowThatSilentlyCutsResearchBackToTenMinutes(){
        String id=initialize();var spec=ScheduleCodec.spec(store.dao().run(id).specJson);
        assertThrows(IllegalArgumentException.class,()->new ScheduleNormalizer().normalize(new ScheduleSpec(spec.title,spec.timing,spec.action,600_000,spec.misfirePolicy),clock(0)));
    }
}
