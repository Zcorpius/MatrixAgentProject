package com.matrix.agent.evaluation;

import android.content.Context;
import android.util.Log;
import androidx.room.Room;
import com.matrix.agent.host.MatrixAgentApplication;
import com.matrix.agent.host.di.AppContainer;
import com.matrix.agent.identity.*;
import com.matrix.agent.data.db.*;
import com.matrix.agent.data.memory.*;
import com.matrix.agent.data.embedding.*;
import com.matrix.agent.embedding.*;
import com.matrix.agent.attachment.*;
import com.matrix.agent.attachment.retrieval.*;
import com.matrix.agent.api.conversation.ConversationAttachment;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Isolated synthetic Room data. No user database mutation, credentials, messages or source documents in reports. */
public final class IntelligenceDeviceProbe {
    private IntelligenceDeviceProbe() { }
    @FunctionalInterface private interface Check { JSONObject run() throws Exception; }
    public static void run(Context context, CancellationToken token) {
        JSONObject report = new JSONObject();
        try {
            AppContainer host = ((MatrixAgentApplication)context).getContainer();
            report.put("schemaVersion",1).put("startedAt",java.time.Instant.now().toString()).put("complete",false)
                    .put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT).put("synthetic",true);
            JSONArray checks = new JSONArray(); report.put("checks",checks);
            check(context,report,checks,"production_encrypted_schema",()-> {
                int version=host.getMatrixDatabase().getOpenHelper().getReadableDatabase().getVersion();
                require(version==20,"production migration version"); return new JSONObject().put("version",version).put("completedChatModels", new JSONArray(host.getModelDownloadDao().getCompleted().stream().map(row->row.modelName).collect(java.util.stream.Collectors.toList())));
            });
            check(context,report,checks,"legacy_room_migrations",()-> migration(context));
            check(context,report,checks,"stream_explicit_json_null",()-> {
                com.matrix.agent.model.StreamingProtocolDeviceCheck.verifyExplicitNull();
                return new JSONObject().put("androidJsonRuntime",true);
            });
            check(context,report,checks,"model_rate_limit_projection",ModelFailureDeviceCheck::verify);
            check(context,report,checks,"research_prompt_budget",ResearchPromptDeviceCheck::verify);
            check(context,report,checks,"packaged_embedding_install",()-> packagedEmbedding(context,token));
            check(context,report,checks,"attachment_bm25_max_corpus",IntelligenceDeviceProbe::maxCorpusRetrieval);
            MatrixDatabase db=Room.inMemoryDatabaseBuilder(context,MatrixDatabase.class).build();
            try {
                check(context,report,checks,"memory_vector_room_lifecycle",()-> memoryLifecycle(db));
                check(context,report,checks,"attachment_room_lifecycle",()-> attachmentLifecycle(db));
                check(context,report,checks,"attachment_pipe_cancellation",()-> attachmentCancellation(db));
                check(context,report,checks,"failure_lesson_room_lifecycle",()-> failureLifecycle(db));
                check(context,report,checks,"embedding_jni_hybrid_comparison",()-> embedding(context,host,db,token));
            } finally { db.close(); }
            report.put("complete",true).put("finishedAt",java.time.Instant.now().toString()); save(context,report);
            Log.i("MatrixIntelligence","completed checks="+checks.length());
        } catch(Exception failed) {
            try {report.put("fatal",failed.getClass().getSimpleName());save(context,report);}catch(Exception ignored) { }
            Log.e("MatrixIntelligence","incomplete cause="+failed.getClass().getSimpleName());
        }
    }
    private static void check(Context context,JSONObject report,JSONArray checks,String name,Check body) throws Exception {
        long start=System.nanoTime(); JSONObject row=new JSONObject().put("name",name); checks.put(row);
        try { row.put("result",body.run()).put("passed",true); }
        catch(Exception | AssertionError failed) { row.put("passed",false).put("failure",failed.getClass().getSimpleName()+":"+Objects.toString(failed.getMessage(),"")); }
        row.put("durationMillis",(System.nanoTime()-start)/1_000_000); save(context,report);
        Log.i("MatrixIntelligence",name+" passed="+row.getBoolean("passed"));
    }
    private static JSONObject packagedEmbedding(Context context, CancellationToken token) throws Exception {
        File root = new File(context.getCacheDir(), "embedding-probe-" + UUID.randomUUID());
        require(root.mkdir(), "isolated model directory");
        var scoped = new android.content.ContextWrapper(context) {
            @Override public File getFilesDir() { return root; }
        };
        var executors = new com.matrix.agent.platform.MatrixExecutorRegistry();
        var graph = new com.matrix.agent.host.di.EmbeddingRuntimeGraph(scoped, null, executors);
        long start = System.nanoTime();
        try {
            float[] vector = graph.encodeOnModelLane("用于验证离线打包安装的合成文本", false, token::isCancelled);
            require(vector.length == 512, "packaged model dimension");
            require(graph.artifact().verifiedConfig().isFile(), "packaged artifact was not installed");
            return new JSONObject().put("isolatedFreshDirectory", true).put("artifactSource", "SIGNED_APK_ASSETS")
                    .put("dimension", vector.length).put("firstInstallAndEncodeMillis", (System.nanoTime()-start)/1_000_000);
        } finally {
            graph.close();
            executors.shutdown();
            try (var paths = Files.walk(root.toPath())) {
                for (var file : paths.sorted(Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) Files.deleteIfExists(file);
            }
        }
    }
    private static JSONObject migration(Context context) throws Exception {
        JSONObject schema;
        try(var input=context.getAssets().open("com.matrix.agent.data.db.MatrixDatabase/16.json")) {
            schema=new JSONObject(new String(read(input),StandardCharsets.UTF_8)).getJSONObject("database");
        }
        String name="intelligence-probe-"+UUID.randomUUID()+".db";
        try {
            File file=context.getDatabasePath(name); file.getParentFile().mkdirs();
            try(var legacy=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)) {
                JSONArray entities=schema.getJSONArray("entities");
                for(int i=0;i<entities.length();i++) {
                    JSONObject entity=entities.getJSONObject(i); String table=entity.getString("tableName");
                    legacy.execSQL(entity.getString("createSql").replace("${TABLE_NAME}",table));
                    JSONArray indices=entity.optJSONArray("indices");
                    if(indices==null) continue;
                    for(int j=0;j<indices.length();j++) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("${TABLE_NAME}",table));
                }
                legacy.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
                legacy.execSQL("INSERT INTO room_master_table VALUES (42,?)",new Object[]{schema.getString("identityHash")});
                legacy.execSQL("INSERT INTO memory_record(userId,zone,layer,`key`,value,score,capturedAtMs) VALUES('probe','driver','semantic','fact.marker','preserved',1,1)");
                legacy.setVersion(16);
            }
            MatrixDatabase db=Room.databaseBuilder(context,MatrixDatabase.class,name)
                    .addMigrations(new com.matrix.agent.data.failure.FailureLessonMigration(),new MemoryVectorMigration(),
                            new com.matrix.agent.data.conversation.AttachmentRetrievalMigration(),new com.matrix.agent.data.schedule.ResearchBudgetMigration()).build();
            try {
                require(db.getOpenHelper().getReadableDatabase().getVersion()==20,"migration version");
                require("preserved".equals(db.memoryRecordDao().queryByKey("probe","driver","semantic","fact.marker").value),"migration lost source row");
                try(var check=db.getOpenHelper().getReadableDatabase().query("PRAGMA foreign_key_check")) {require(!check.moveToFirst(),"foreign key violation");}
            } finally { db.close(); }
            return new JSONObject().put("from",16).put("to",20).put("sourceRowPreserved",true).put("isolatedPlainSqlite",true);
        } finally { context.deleteDatabase(name); }
    }
    private static JSONObject memoryLifecycle(MatrixDatabase db) throws Exception {
        var scope=new MemoryScope("probe-vector",VehicleZone.DRIVER); var vectorStore=new RoomMemoryVectorStore(db);
        put(db,scope,"fact.topic","old",1); var old=vectorStore.sources(scope,0).get(0);
        require(vectorStore.commit(old,"v1",new float[]{1,0}),"initial CAS");
        put(db,scope,"fact.topic","new",2);
        require(vectorStore.load(scope,0,"v1",2).isEmpty(),"REPLACE must cascade vector");
        require(!vectorStore.commit(old,"v1",new float[]{1,0}),"stale update CAS");
        var current=vectorStore.sources(scope,0).get(0);
        require(vectorStore.commit(current,"v1",new float[]{1,0}),"new CAS");
        db.memoryRecordDao().deleteByKey(scope.getUserId(),scope.getZone().wireValue(),"semantic","fact.topic");
        require(vectorStore.load(scope,0,"v1",2).isEmpty()&&!vectorStore.commit(current,"v1",new float[]{1,0}),"delete resurrected vector");
        put(db,scope,"fact.topic","reset",3); var beforeReset=vectorStore.sources(scope,0).get(0);
        var memory=new RoomMemoryStore(db,Runnable::run); memory.clearUserDataAndBump(scope.getUserId(),scope.getZone().wireValue());
        require(!vectorStore.commit(beforeReset,"v1",new float[]{1,0}),"epoch resurrected vector");
        return new JSONObject().put("replaceCascade",true).put("deleteCAS",true).put("epochCAS",true);
    }
    private static JSONObject maxCorpusRetrieval() throws Exception {
        List<DocumentChunk> chunks = new ArrayList<>();
        long sourceBytes = 0;
        for (int i = 0; i < 4; i++) {
            String text = "常规记录".repeat(170_000) + (i == 3 ? "星河目标仅在文末" : "常规结尾");
            sourceBytes += text.getBytes(StandardCharsets.UTF_8).length;
            chunks.addAll(DocumentChunker.split("benchmark-" + i, text));
        }
        long started = System.nanoTime();
        var ranked = new Bm25Retriever().rank(chunks, "星河目标", 8);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        require(!ranked.isEmpty() && ranked.get(0).chunk().text().contains("星河目标"),
                "max corpus tail evidence missing");
        return new JSONObject().put("sourceBytes", sourceBytes).put("chunks", chunks.size())
                .put("rankMillis", elapsedMillis).put("tailRetrieved", true);
    }

    private static JSONObject attachmentLifecycle(MatrixDatabase db) throws Exception {
        var store=new RoomAttachmentStagingStore(db.conversationAttachmentDao(),db.attachmentChunkDao(),db::runInTransaction);
        String owner="probe-attachment",conv="probe-conversation";
        String text="合成背景材料。\n".repeat(3000)+"\n文末口令为星河七号。\n";
        var started=store.beginStage(owner,"driver",conv,"text/plain","synthetic.txt",UUID.randomUUID().toString());
        store.completeStage(started.attachment().attachmentId,new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),"text/plain");
        String id=started.attachment().attachmentId;
        var parent=db.conversationAttachmentDao().getById(id); require(parent.state==ConversationAttachment.STATE_READY,"attachment ingestion");
        var chunks=db.attachmentChunkDao().load(owner,"driver",conv,List.of(id)); require(chunks.size()>20,"tail chunks absent");
        var ranked=new Bm25Retriever().rank(chunks.stream().map(row->new DocumentChunk(row.attachmentId,row.ordinal,row.contentVersion,row.startChar,row.endChar,row.text)).collect(java.util.stream.Collectors.toList()),"文末口令",3);
        require(ranked.stream().anyMatch(hit->hit.chunk().text().contains("星河七号")),"tail retrieval");
        require(db.attachmentChunkDao().load(owner,"passenger",conv,List.of(id)).isEmpty(),"attachment zone leak");
        db.conversationAttachmentDao().deleteById(id);
        require(db.attachmentChunkDao().storageBytes(owner,"driver")==0,"attachment cascade");
        var invalid=store.beginStage(owner,"driver",conv,"text/plain","invalid.txt",UUID.randomUUID().toString());
        store.completeStage(invalid.attachment().attachmentId,new ByteArrayInputStream(new byte[]{(byte)0xff}),"text/plain");
        require(db.conversationAttachmentDao().getById(invalid.attachment().attachmentId).state==ConversationAttachment.STATE_FAILED,"invalid UTF-8 accepted");
        require(db.attachmentChunkDao().storageBytes(owner,"driver")==0,"failed attachment left index");
        return new JSONObject().put("fullChars",text.length()).put("chunks",chunks.size()).put("tailRetrieved",true).put("deleteCascade",true).put("failedNoIndex",true);
    }
    private static JSONObject failureLifecycle(MatrixDatabase db) throws Exception {
        var memory=new RoomMemoryStore(db,Runnable::run); long epoch=memory.currentEpoch();
        var store=new com.matrix.agent.data.failure.RoomFailureLessonStore(db); var scope=new MemoryScope("probe-lesson",VehicleZone.DRIVER);
        long now=System.currentTimeMillis(); var lesson=new com.matrix.agent.failure.FailureLesson(1,com.matrix.agent.failure.FailureLesson.Code.CHECK_PARAMETERS,List.of(0));
        var entry=new com.matrix.agent.failure.FailureLessonStore.Entry(scope,"synthetic-task",epoch,now,"schedule.create",lesson);
        require(store.save(entry)&&!store.save(entry),"lesson deduplication");
        require(store.recall(scope,epoch,Set.of("schedule.create"),now,3).size()==1,"lesson recall");
        require(store.recall(new MemoryScope("probe-lesson",VehicleZone.PASSENGER),epoch,Set.of("schedule.create"),now,3).isEmpty(),"lesson zone leak");
        require(store.recall(scope,epoch,Set.of("schedule.create"),now+com.matrix.agent.data.failure.RoomFailureLessonStore.TTL_MILLIS,3).isEmpty(),"lesson TTL");
        try(var rows=db.getOpenHelper().getReadableDatabase().query("SELECT count(*) FROM failure_lesson WHERE owner='probe-lesson'")) {
            require(rows.moveToFirst()&&rows.getInt(0)==1,"recall performed a write");
        }
        var later=new com.matrix.agent.failure.FailureLessonStore.Entry(scope,"synthetic-task-next",epoch,
                now+com.matrix.agent.data.failure.RoomFailureLessonStore.TTL_MILLIS,"schedule.create",lesson);
        require(store.save(later),"save-side TTL pruning");
        memory.clearUserDataAndBump(scope.getUserId(),scope.getZone().wireValue());
        require(!store.save(later)&&store.recall(scope,memory.currentEpoch(),Set.of("schedule.create"),now,3).isEmpty(),"lesson reset resurrection");
        return new JSONObject().put("deduplicated",true).put("scoped",true).put("ttl",true)
                .put("readOnlyRecall",true).put("epochCAS",true);
    }
    private static JSONObject attachmentCancellation(MatrixDatabase db) throws Exception {
        var store=new RoomAttachmentStagingStore(db.conversationAttachmentDao(),db.attachmentChunkDao(),db::runInTransaction);
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for(boolean clear:List.of(false,true)) {
                var pipe=android.os.ParcelFileDescriptor.createReliablePipe();
                var input=new com.matrix.agent.host.rpc.AttachmentInputStream(pipe[0],1000);
                var entered=new java.util.concurrent.CountDownLatch(1);
                var started=store.beginStage("probe-pipe","driver","pipe-conversation","text/plain","pipe.txt",UUID.randomUUID().toString());
                try {
                    var task=worker.submit(()->store.completeStage(started.attachment().attachmentId,new FilterInputStream(input){
                        @Override public int read(byte[] b,int off,int len)throws IOException {entered.countDown();return super.read(b,off,len);}
                    },"text/plain"));
                    require(entered.await(2,java.util.concurrent.TimeUnit.SECONDS),"pipe read never entered");
                    if(clear)store.clearForUsers(List.of("probe-pipe"));
                    else require(store.deleteDraft("probe-pipe","driver",started.attachment().attachmentId),"draft not deleted");
                    task.get(2,java.util.concurrent.TimeUnit.SECONDS);
                    require(db.conversationAttachmentDao().getById(started.attachment().attachmentId)==null,"cancelled ingestion resurrected parent");
                    require(db.attachmentChunkDao().storageBytes("probe-pipe","driver")==0,"cancelled ingestion left index");
                } finally {input.close();pipe[1].close();}
            }
            var pipe=android.os.ParcelFileDescriptor.createReliablePipe();long start=System.nanoTime();boolean timedOut=false;
            try(var input=new com.matrix.agent.host.rpc.AttachmentInputStream(pipe[0],100)) {
                try {input.read();}catch(java.io.InterruptedIOException expected){timedOut=true;}
            } finally {pipe[1].close();}
            require(timedOut&&(System.nanoTime()-start)/1_000_000<1000,"idle pipe exceeded absolute deadline");
            return new JSONObject().put("deleteUnblocked",true).put("clearUnblocked",true).put("deadlineUnblocked",true);
        } finally {worker.shutdownNow();}
    }
    private static JSONObject embedding(Context context,AppContainer host,MatrixDatabase db,CancellationToken token) throws Exception {
        var graph=host.getEmbeddingGraph(); File incoming=new File(context.getExternalFilesDir(null),"embedding-import");
        graph.artifact().install(name->new FileInputStream(new File(incoming,name)),token::isCancelled);
        var profile=graph.artifact().profile(); var scope=new MemoryScope("probe-retrieval",VehicleZone.DRIVER);
        JSONObject corpus;
        try(var in=context.getAssets().open("semantic-recall-v1.json")) {corpus=new JSONObject(new String(read(in),StandardCharsets.UTF_8));}
        JSONArray documents=corpus.getJSONArray("documents");
        for(int i=0;i<documents.length();i++) {var doc=documents.getJSONObject(i);put(db,scope,doc.getString("key"),doc.getString("value"),i+1);}
        var store=new RoomMemoryVectorStore(db);
        var index=new MemoryEmbeddingIndex(store,new MemoryEmbeddingIndex.Encoder() {
            public String modelVersion(){return profile.modelVersion();} public int dimension(){return profile.dimension();}
            public float[] encode(String text,boolean query,java.util.function.BooleanSupplier cancelled)throws Exception{return graph.encodeOnModelLane(text,query,cancelled);}
        },Runnable::run,.50);
        try {
            index.scheduleBackfill(scope,store.currentEpoch());
            require(store.load(scope,store.currentEpoch(),profile.modelVersion(),512).size()==documents.length(),"JNI backfill missing vectors");
            JSONArray samples=new JSONArray(),queries=corpus.getJSONArray("queries");
            for(int i=0;i<queries.length();i++) {
                if(token.isCancelled())throw new InterruptedException();
                var query=queries.getJSONObject(i); JSONObject sample=new JSONObject().put("queryIndex",i).put("split",query.getString("split")).put("expected",query.getJSONArray("expected"));
                for(var mode:SemanticMemorySourceImpl.Mode.values()) {
                    long start=System.nanoTime(); var hits=new SemanticMemorySourceImpl(db.memoryRecordDao(),5,index,mode).recallSemantic(scope,query.getString("text"),5);
                    sample.put(mode.name(),new JSONObject().put("keys",new JSONArray(hits.stream().map(MemorySnippet::getKey).collect(java.util.stream.Collectors.toList()))).put("durationMillis",(System.nanoTime()-start)/1_000_000));
                }
                samples.put(sample);
            }
            return new JSONObject().put("modelVersion",profile.modelVersion()).put("dimension",512).put("minimumCosine",.50).put("documents",documents.length()).put("samples",samples)
                    .put("realJni",true).put("productionRetrieval",true).put("endToEndAnswerQuality",JSONObject.NULL);
        } finally {index.close();}
    }
    private static void put(MatrixDatabase db,MemoryScope scope,String key,String value,long time) {
        var row=new MemoryRecordEntity();row.userId=scope.getUserId();row.zone=scope.getZone().wireValue();row.layer="semantic";row.key=key;row.value=value;row.score=1;row.capturedAtMs=time;row.sourceSessionId="synthetic";db.memoryRecordDao().upsert(row);
    }
    private static byte[] read(InputStream input) throws IOException {
        var bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];
        for(int count;(count=input.read(buffer))!=-1;) {if(bytes.size()+count>2*1024*1024)throw new IOException("probe input bound");bytes.write(buffer,0,count);}return bytes.toByteArray();
    }
    private static void require(boolean condition,String message) {if(!condition)throw new AssertionError(message);}
    private static void save(Context context,JSONObject report)throws Exception {
        Path directory=new File(context.getExternalFilesDir(null),"verification").toPath();Files.createDirectories(directory);
        Path temp=directory.resolve("intelligence-device.tmp");Files.write(temp,report.toString(2).getBytes(StandardCharsets.UTF_8));
        Files.move(temp,directory.resolve("intelligence-device.json"),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
