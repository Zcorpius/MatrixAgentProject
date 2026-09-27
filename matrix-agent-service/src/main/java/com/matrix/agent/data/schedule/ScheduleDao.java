package com.matrix.agent.data.schedule;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/** Flat persistence commands. The store wraps related operations in one Room transaction. */
@Dao
public interface ScheduleDao {
    @Insert void insertDefinition(ScheduleDefinitionEntity row);
    @Update int updateDefinition(ScheduleDefinitionEntity row);
    @Query("SELECT * FROM schedule_definition WHERE scheduleId=:id") ScheduleDefinitionEntity definition(String id);
    @Query("SELECT * FROM schedule_definition WHERE ownerUid=:uid AND state!=5 AND scheduleId>:after ORDER BY scheduleId LIMIT :limit")
    List<ScheduleDefinitionEntity> definitions(int uid, String after, int limit);
    @Query("SELECT * FROM schedule_definition WHERE state=2 ORDER BY nextDueAt, scheduleId LIMIT 100")
    List<ScheduleDefinitionEntity> activeDefinitions();
    @Query("SELECT COUNT(*) FROM schedule_definition WHERE ownerUserId=:user AND state=2") int activeCount(int user);

    @Insert void insertRun(ScheduleRunEntity row);
    @Update int updateRun(ScheduleRunEntity row);
    @Query("SELECT * FROM schedule_run WHERE runId=:id") ScheduleRunEntity run(String id);
    @Query("SELECT * FROM schedule_run WHERE scheduleId=:id AND occurrenceKey=:key")
    ScheduleRunEntity occurrence(String id, String key);
    @Query("SELECT * FROM schedule_run WHERE ownerUid=:uid AND (:schedule='' OR scheduleId=:schedule) AND (scheduledAt<:beforeTime OR (scheduledAt=:beforeTime AND runId<:beforeId)) ORDER BY scheduledAt DESC, runId DESC LIMIT :limit")
    List<ScheduleRunEntity> runs(int uid, String schedule, long beforeTime, String beforeId, int limit);
    @Query("SELECT * FROM schedule_run WHERE scheduleId=:schedule AND (state IN (1,2,7,10,11,12) OR deliveryStatus=1) ORDER BY scheduledAt LIMIT 100")
    List<ScheduleRunEntity> unresolvedRuns(String schedule);
    @Query("SELECT * FROM schedule_run WHERE state IN (1,2,7,10,11,12) ORDER BY scheduledAt LIMIT 1000")
    List<ScheduleRunEntity> unfinishedRuns();
    @Query("UPDATE schedule_run SET dispatchClaimed=1 WHERE runId=:id AND dispatchGeneration=:generation AND dispatchClaimed=0 AND state=1 AND dataEpoch=:epoch")
    int claim(String id, long generation, long epoch);

    @Insert(onConflict=OnConflictStrategy.REPLACE) void putOutbox(ScheduleOutboxEntity row);
    @Query("SELECT * FROM schedule_outbox WHERE effectId=:id") ScheduleOutboxEntity outbox(String id);
    @Query("SELECT * FROM schedule_outbox WHERE state=0 AND dataEpoch=:epoch ORDER BY nextAttemptAt,effectId LIMIT 256")
    List<ScheduleOutboxEntity> pendingOutbox(long epoch);
    @Query("DELETE FROM schedule_outbox WHERE effectId=:id") void deleteOutbox(String id);
    @Query("DELETE FROM schedule_outbox WHERE runId=:run") void deleteRunOutbox(String run);
    @Query("DELETE FROM schedule_outbox WHERE scheduleId=:schedule") void deleteScheduleOutbox(String schedule);

    @Insert long insertEvent(ScheduleEventEntity row);
    @Query("SELECT COALESCE(MAX(sequence),0) FROM schedule_event WHERE ownerUid=:uid") long sequence(int uid);
    @Query("SELECT * FROM schedule_event WHERE ownerUid=:uid AND sequence>:after ORDER BY sequence LIMIT :limit")
    List<ScheduleEventEntity> events(int uid, long after, int limit);
    @Update int updateControl(ScheduleControlEntity row);
    @Insert void insertControl(ScheduleControlEntity row);
    @Query("SELECT * FROM schedule_control WHERE ownerUid=:uid AND operationId=:id")
    ScheduleControlEntity control(int uid, String id);

    @Insert(onConflict=OnConflictStrategy.REPLACE) void putArm(ScheduleArmEntity row);
    @Query("SELECT * FROM schedule_arm WHERE ownerUserId=0") ScheduleArmEntity arm();
    @Insert void insertStep(ScheduleStepEntity row);
    @Update int updateStep(ScheduleStepEntity row);
    @Query("SELECT * FROM schedule_step WHERE runId=:run ORDER BY stepId") List<ScheduleStepEntity> steps(String run);
    @Query("SELECT * FROM schedule_step WHERE runId=:run AND stepId=:step") ScheduleStepEntity step(String run, String step);
    @Insert void insertAcceptance(ScheduleAcceptanceEntity row);
    @Update int updateAcceptance(ScheduleAcceptanceEntity row);
    @Query("SELECT * FROM schedule_acceptance WHERE runtimeRequestId=:id") ScheduleAcceptanceEntity acceptance(String id);
    @Query("SELECT * FROM schedule_acceptance WHERE state IN (1,2) ORDER BY acceptedAt LIMIT 100")
    List<ScheduleAcceptanceEntity> pendingAcceptances();

    @Query("SELECT * FROM schedule_definition WHERE state!=5 AND json_extract(timeRuleJson, '$.binding')=:binding ORDER BY CASE WHEN state=2 THEN 0 ELSE 1 END,scheduleId LIMIT 1000")
    List<ScheduleDefinitionEntity> calendarDefinitions(String binding);
    @Query("SELECT * FROM schedule_binding WHERE EXISTS (SELECT 1 FROM schedule_definition d WHERE json_extract(d.timeRuleJson, '$.binding')=schedule_binding.bindingId AND (d.state=2 OR EXISTS (SELECT 1 FROM schedule_run r WHERE r.scheduleId=d.scheduleId AND r.state IN (1,2,10,11,12)))) ORDER BY bindingId LIMIT 1000") List<ScheduleBindingEntity> allBindings();
    @Insert(onConflict=OnConflictStrategy.REPLACE) void putBinding(ScheduleBindingEntity row);
    @Query("SELECT * FROM schedule_binding WHERE bindingId=:id") ScheduleBindingEntity binding(String id);
    @Query("SELECT * FROM schedule_binding WHERE ownerUid=:uid ORDER BY bindingId LIMIT 100")
    List<ScheduleBindingEntity> bindings(int uid);

    @Query("SELECT runId FROM schedule_run WHERE state IN (3,4,5,6,8,9) AND deliveryStatus!=1 AND (completedAt<:cutoff OR runId NOT IN (SELECT runId FROM schedule_run ORDER BY scheduledAt DESC,runId DESC LIMIT 1000)) AND NOT EXISTS (SELECT 1 FROM schedule_outbox WHERE schedule_outbox.runId=schedule_run.runId) ORDER BY completedAt LIMIT 100")
    List<String> expiredRuns(long cutoff);
    @Query("DELETE FROM schedule_acceptance WHERE runId=:id") void deleteRunAcceptances(String id);
    @Query("DELETE FROM schedule_step WHERE runId=:id") void deleteRunSteps(String id);
    @Query("DELETE FROM schedule_run WHERE runId=:id") void deleteRun(String id);
    @Query("DELETE FROM schedule_event WHERE sequence NOT IN (SELECT sequence FROM schedule_event ORDER BY sequence DESC LIMIT 5000)") void pruneEvents();
    @Query("SELECT COALESCE(MIN(sequence),0) FROM schedule_event WHERE ownerUid=:uid") long firstSequence(int uid);
    @Query("DELETE FROM schedule_binding WHERE lastVerifiedAt<:cutoff AND NOT EXISTS (SELECT 1 FROM schedule_definition WHERE json_extract(timeRuleJson, '$.binding')=schedule_binding.bindingId AND state!=5)") void pruneBindings(long cutoff);

    @Query("DELETE FROM schedule_definition") void clearDefinitions();
    @Query("DELETE FROM schedule_run") void clearRuns();
    @Query("DELETE FROM schedule_outbox") void clearOutbox();
    @Query("DELETE FROM schedule_event") void clearEvents();
    @Query("DELETE FROM schedule_control") void clearControls();
    @Query("DELETE FROM schedule_arm") void clearArms();
    @Query("DELETE FROM schedule_step") void clearSteps();
    @Query("DELETE FROM schedule_acceptance") void clearAcceptances();
    @Query("DELETE FROM schedule_binding") void clearBindings();

    default void clearAll() {
        clearOutbox(); clearAcceptances(); clearSteps(); clearRuns(); clearBindings();
        clearEvents(); clearControls(); clearArms(); clearDefinitions();
    }
}
