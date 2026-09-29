package com.matrix.agent.evaluation;

import android.content.Context;
import android.util.Log;
import androidx.room.Room;
import com.matrix.agent.host.MatrixAgentApplication;
import com.matrix.agent.identity.*;
import com.matrix.agent.contract.*;
import com.matrix.agent.model.*;
import com.matrix.agent.task.*;
import com.matrix.agent.failure.*;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.failure.RoomFailureLessonStore;
import com.matrix.agent.data.memory.MemoryScope;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen repeated-task A/B. Advice is learned only from actual eligible failure traces, never scorer guesses. */
public final class ReflectionDeviceProbe {
    private ReflectionDeviceProbe() { }
    public static void run(Context context,CancellationToken parent) {
        MatrixDatabase db=null;
        try {
            var host=((MatrixAgentApplication)context).getContainer();var config=host.getModelConfigStore().load();
            if(config==null)throw new IllegalStateException("real model configuration required");
            var client=new ModelApiClient(host.getHttpClient().provider());var gateway=new LlmModelGateway(client,config);
            var reflection=new LlmFailureReflectionModel(client,()->config);
            Path assets=context.getCacheDir().toPath().resolve("evaluation-inputs");
            List<EvaluationCase> corpus=Corpus.load(assets.resolve("evaluation/corpus-v1.json"));
            Set<String> selected=Set.of("media-qq-no-session","media-bili-no-session","multiturn-qq-expired","schedule-delay-5");
            JSONObject report=new JSONObject().put("schemaVersion",1).put("synthetic",true).put("model",config.model).put("protocol",config.protocol.name())
                    .put("selectedBeforeRun",new JSONArray(selected)).put("repetitions",2).put("reflectionDeadlineMillis",8000).put("productionDefaultEnabled",false)
                    .put("complete",false).put("corpusSha256",Provenance.sha256(Files.readAllBytes(assets.resolve("evaluation/corpus-v1.json"))));
            JSONArray samples=new JSONArray();report.put("samples",samples);save(context,report);
            db=Room.inMemoryDatabaseBuilder(context,MatrixDatabase.class).build();var store=new RoomFailureLessonStore(db);
            var scope=new MemoryScope(ActorUsers.USER_DRIVER,VehicleZone.DRIVER);
            try(var harness=new EvaluationHarness(assets)) {
                for(var scenario:corpus)if(selected.contains(scenario.id())) {
                    if(parent.isCancelled())throw new InterruptedException();
                    store.delete(scope);
                    var training=harness.run(scenario,gateway);JSONArray lessons=new JSONArray();
                    for(var trace:training) {
                        TaskState state=trace.outcome().getFinalState();if(state!=TaskState.FAILED&&state!=TaskState.PARTIALLY_SUCCEEDED)continue;
                        var evidence=FailureEvidence.project(trace.outcome());var expected=FailureLesson.deterministic(evidence);
                        var child=new CancellationToken();Runnable abort=child::cancel;parent.registerAbortHook(abort);
                        FailureLesson learned=FailureLesson.unknown();String failure="";long start=System.nanoTime();
                        try{learned=reflection.reflect(evidence,child,System.currentTimeMillis()+8000);}catch(Exception unavailable){failure=unavailable.getClass().getSimpleName();}
                        finally{child.cancel();parent.removeAbortHook(abort);}
                        boolean grounded=learned.groundedIn(evidence);
                        if(grounded)for(int ref:learned.evidenceRefs())store.save(new FailureLessonStore.Entry(scope,trace.outcome().getRequestId(),0,System.currentTimeMillis(),evidence.items().get(ref).capability(),learned));
                        lessons.put(new JSONObject().put("expectedCode",expected.lessonCode().name()).put("learnedCode",learned.lessonCode().name()).put("grounded",grounded)
                                .put("classificationCorrect",expected.lessonCode()==learned.lessonCode()).put("failure",failure).put("durationMillis",(System.nanoTime()-start)/1_000_000));
                    }
                    for(int repeat=1;repeat<=2;repeat++)for(boolean enabled:repeat==1?List.of(false,true):List.of(true,false)) {
                        if(parent.isCancelled())throw new InterruptedException();
                        var traces=harness.run(scenario,gateway,new FailureLessonRecaller(store,()->enabled));
                        boolean passed=true,unsafe=false;JSONArray turns=new JSONArray();
                        for(int i=0;i<traces.size();i++){var trace=traces.get(i);var score=new GoalScorer().score(scenario.turns().get(i).expect(),trace);passed&=score.passed();unsafe|=score.unsafeExecution();
                            turns.put(new JSONObject().put("passed",score.passed()).put("failure",score.primary()==null?JSONObject.NULL:score.primary().name())
                                    .put("durationMillis",trace.outcome().getDurationMillis()).put("modelCalls",trace.modelCalls()).put("answerSha256",Provenance.sha256(Objects.toString(trace.outcome().getFinalAssistantText(),""))));}
                        samples.put(new JSONObject().put("case",scenario.id()).put("repeat",repeat).put("reflectionEnabled",enabled).put("learnedLessons",lessons)
                                .put("passed",passed).put("unsafeExecution",unsafe).put("turns",turns));save(context,report);
                        Log.i("MatrixReflectionProbe",scenario.id()+" repeat="+repeat+" enabled="+enabled+" passed="+passed);
                    }
                }
            }
            report.put("complete",true);save(context,report);Log.i("MatrixReflectionProbe","completed samples="+samples.length());
        }catch(Exception failed){Log.e("MatrixReflectionProbe","incomplete="+failed.getClass().getSimpleName());}
        finally{if(db!=null)db.close();}
    }
    private static void save(Context context,JSONObject report)throws Exception {
        Path dir=context.getExternalFilesDir(null).toPath().resolve("verification");Files.createDirectories(dir);Path temp=dir.resolve("reflection-device.tmp");
        Files.write(temp,report.toString(2).getBytes(StandardCharsets.UTF_8));Files.move(temp,dir.resolve("reflection-device.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
