package com.matrix.agent.schedule.store;
import com.matrix.agent.data.db.*;
import com.matrix.agent.data.schedule.*;
import java.util.*;
import java.util.concurrent.Callable;

/** In-memory transactional fixture. Device tests separately verify Room, SQLCipher and Android effects. */
public final class ScheduleStoreFixture extends MatrixDatabase {
    /** Detached rows for effect-based evaluators; never exposes mutable fixture storage. */
    public List<ScheduleDefinitionEntity> definitions() {
        return rows("plans", ScheduleDefinitionEntity.class, row -> true);
    }
    final Map<String, LinkedHashMap<String, Object>> tables = new HashMap<>();
    long sequence;
    String failNext = "";
    final ScheduleDao dao = (ScheduleDao) java.lang.reflect.Proxy.newProxyInstance(ScheduleDao.class.getClassLoader(),
            new Class[]{ScheduleDao.class}, (proxy, method, args) -> invoke(method.getName(), args == null ? new Object[0] : args));
    @Override public boolean isOpen() { return true; }
    @Override protected androidx.room.InvalidationTracker createInvalidationTracker() { return null; }
    @Override public void clearAllTables() { tables.clear(); }
    @Override public synchronized void runInTransaction(Runnable action) { runInTransaction(() -> { action.run(); return null; }); }
    @Override public synchronized <T> T runInTransaction(Callable<T> action) {
        Map<String, LinkedHashMap<String, Object>> backup = new HashMap<>();
        tables.forEach((name, rows) -> { var copied = new LinkedHashMap<String, Object>(); rows.forEach((key, value) -> copied.put(key, copy(value))); backup.put(name, copied); });
        long oldSequence = sequence;
        try { return action.call(); }
        catch (RuntimeException failure) { tables.clear(); tables.putAll(backup); sequence = oldSequence; throw failure; }
        catch (Exception failure) { tables.clear(); tables.putAll(backup); sequence = oldSequence; throw new IllegalStateException(failure); }
    }
    @Override public TrajectoryDao trajectoryDao() { return null; }
    @Override public SessionHistoryDao sessionHistoryDao() { return null; }
    @Override public MemoryRecordDao memoryRecordDao() { return null; }
    @Override public com.matrix.agent.data.failure.FailureLessonDao failureLessonDao() { return null; }
    @Override public com.matrix.agent.data.embedding.MemoryVectorDao memoryVectorDao() { return null; }
    @Override public com.matrix.agent.data.conversation.AttachmentChunkDao attachmentChunkDao() { return null; }
    @Override public AuditEventDao auditEventDao() { return null; }
    @Override public ModelDownloadDao modelDownloadDao() { return null; }
    @Override public AgentTaskDao agentTaskDao() { return null; }
    @Override public com.matrix.agent.data.conversation.ConversationDao conversationDao() { return null; }
    @Override public com.matrix.agent.data.conversation.ConversationMessageDao conversationMessageDao() { return null; }
    @Override public com.matrix.agent.data.conversation.ConversationTaskLinkDao conversationTaskLinkDao() { return null; }
    @Override public com.matrix.agent.data.conversation.ConversationMessageAnnotationDao conversationMessageAnnotationDao() { return null; }
    @Override public com.matrix.agent.data.conversation.ConversationQuoteDao conversationQuoteDao() { return null; }
    @Override public com.matrix.agent.data.conversation.ConversationLineageDao conversationLineageDao() { return null; }
    @Override public com.matrix.agent.data.conversation.ConversationDraftDao conversationDraftDao() { return null; }
    @Override public com.matrix.agent.data.conversation.ConversationAttachmentDao conversationAttachmentDao() { return null; }
    @Override public com.matrix.agent.data.debugtrace.DebugTraceEventDao debugTraceEventDao() { return null; }
    @Override public com.matrix.agent.data.schedule.ScheduleDao scheduleDao() { return dao; }
    private LinkedHashMap<String, Object> table(String name) { return tables.computeIfAbsent(name, key -> new LinkedHashMap<>()); }
    private Object get(String table, Object key) { return copy(table(table).get(key.toString())); }
    private void put(String table, Object key, Object row, boolean insert) {
        if (insert && table(table).containsKey(key.toString())) throw new IllegalStateException("unique primary key");
        table(table).put(key.toString(), copy(row));
    }
    private static <T> T copy(T row) {
        if (row == null) return null;
        try { Object result = row.getClass().getConstructor().newInstance(); for (var field : row.getClass().getFields()) field.set(result, field.get(row)); return (T) result; }
        catch (Exception failed) { throw new IllegalStateException(failed); }
    }
    private <T> List<T> rows(String table, Class<T> type, java.util.function.Predicate<T> filter) {
        List<T> result = new ArrayList<>(); for (Object row : table(table).values()) if (filter.test(type.cast(row))) result.add(copy(type.cast(row))); return result;
    }
    private Object invoke(String name, Object[] a) {
        if (name.equals(failNext)) { failNext = ""; throw new IllegalStateException("injected persistence failure: " + name); }
        switch (name) {
            case "insertDefinition", "updateDefinition" -> { var row = (ScheduleDefinitionEntity) a[0]; put("plans", row.scheduleId, row, name.startsWith("insert")); return 1; }
            case "definition" -> { return get("plans", a[0]); }
            case "definitions" -> {
                return rows("plans", ScheduleDefinitionEntity.class,
                        row -> row.ownerUid == (Integer) a[0] && row.state != 5 && row.scheduleId.compareTo((String) a[1]) > 0)
                        .stream().sorted(Comparator.comparing(row -> row.scheduleId))
                        .limit((Integer) a[2]).toList();
            }
            case "activeCount" -> { return rows("plans", ScheduleDefinitionEntity.class, row -> row.state == 2).size(); }
            case "activeDefinitions" -> { return rows("plans", ScheduleDefinitionEntity.class, row -> row.state == 2); }
            case "insertRun", "updateRun" -> { var row = (ScheduleRunEntity) a[0];
                if (name.equals("insertRun")) for (ScheduleRunEntity other : rows("runs", ScheduleRunEntity.class, value -> true)) {
                    if (other.scheduleId.equals(row.scheduleId) && other.occurrenceKey.equals(row.occurrenceKey) || other.runtimeRequestId.equals(row.runtimeRequestId)) throw new IllegalStateException("unique occurrence or request");
                }
                put("runs", row.runId, row, name.startsWith("insert")); return 1;
            }
            case "run" -> { return get("runs", a[0]); }
            case "occurrence" -> { return rows("runs", ScheduleRunEntity.class, row -> row.scheduleId.equals(a[0]) && row.occurrenceKey.equals(a[1])).stream().findFirst().orElse(null); }
            case "unresolvedRuns" -> { return rows("runs", ScheduleRunEntity.class, row -> row.scheduleId.equals(a[0]) && (Set.of(1,2,7,10,11,12).contains(row.state) || row.deliveryStatus == 1)); }
            case "claim" -> { var row = (ScheduleRunEntity) get("runs", a[0]);
                if (row == null || row.dispatchClaimed || row.state != 1 || row.dispatchGeneration != (Long)a[1] || row.dataEpoch != (Long)a[2]) return 0;
                row.dispatchClaimed = true; put("runs", row.runId, row, false); return 1;
            }
            case "putOutbox" -> { var row = (ScheduleOutboxEntity)a[0]; put("outbox", row.effectId, row, false); return null; }
            case "outbox" -> { return get("outbox", a[0]); }
            case "deleteOutbox" -> { table("outbox").remove(a[0]); return null; }
            case "deleteScheduleOutbox", "deleteRunOutbox" -> { table("outbox").values().removeIf(value -> { var row=(ScheduleOutboxEntity)value; return (name.equals("deleteRunOutbox") ? row.runId : row.scheduleId).equals(a[0]); }); return null; }
            case "pendingOutbox" -> { var result = rows("outbox", ScheduleOutboxEntity.class, row -> row.state == 0 && row.dataEpoch == (Long)a[0]); result.sort(Comparator.comparingLong(row -> row.nextAttemptAt)); return result; }
            case "insertEvent" -> { var row=(ScheduleEventEntity)a[0]; row.sequence=++sequence; put("events", row.sequence, row, true); return sequence; }
            case "sequence" -> { return sequence; }
            case "putBinding" -> { var row=(ScheduleBindingEntity)a[0]; put("bindings",row.bindingId,row,false); return null; }
            case "binding" -> { return get("bindings",a[0]); }
            case "bindings" -> { return rows("bindings",ScheduleBindingEntity.class,row -> row.ownerUid==(Integer)a[0]); }
            case "allBindings" -> { return rows("bindings",ScheduleBindingEntity.class,row -> true); }
            case "calendarDefinitions" -> { return rows("plans",ScheduleDefinitionEntity.class,row -> row.state!=5 && com.matrix.agent.schedule.domain.ScheduleCodec.rule(row.timeRuleJson) instanceof com.matrix.agent.schedule.domain.TimeRule.CalendarOffset calendar && calendar.bindingId().equals(a[0])); }
            case "insertControl" -> { var row=(ScheduleControlEntity)a[0]; put("controls", row.ownerUid+"/"+row.operationId, row, true); return null; }
            case "control" -> { return get("controls", a[0]+"/"+a[1]); }
            case "putArm" -> { put("arms", 0, a[0], false); return null; }
            case "arm" -> { return get("arms", 0); }
            case "insertAcceptance", "updateAcceptance" -> { var row=(ScheduleAcceptanceEntity)a[0]; put("acceptance", row.runtimeRequestId, row, name.startsWith("insert")); return 1; }
            case "acceptance" -> { return get("acceptance", a[0]); }
            case "insertStep", "updateStep" -> { var row=(ScheduleStepEntity)a[0]; put("steps", row.runId+"/"+row.stepId, row, name.startsWith("insert")); return 1; }
            case "step" -> { return get("steps", a[0]+"/"+a[1]); }
            case "steps" -> { return rows("steps", ScheduleStepEntity.class, row -> row.runId.equals(a[0])); }
            default -> throw new UnsupportedOperationException(name);
        }
    }
}
