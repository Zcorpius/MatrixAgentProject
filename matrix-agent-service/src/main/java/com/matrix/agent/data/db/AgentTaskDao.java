package com.matrix.agent.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface AgentTaskDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) void insert(AgentTaskEntity entity);
    @Update void update(AgentTaskEntity entity);
    @Query("SELECT * FROM agent_task WHERE taskId = :taskId LIMIT 1")
    AgentTaskEntity get(String taskId);
    @Query("SELECT * FROM agent_task WHERE taskId = :taskId AND ownerUid = :ownerUid LIMIT 1")
    AgentTaskEntity getOwned(String taskId, int ownerUid);
    @Query("SELECT * FROM agent_task WHERE ownerUid = :ownerUid AND clientRequestId = :clientRequestId LIMIT 1")
    AgentTaskEntity findByClientRequest(int ownerUid, String clientRequestId);
    @Query("SELECT * FROM agent_task WHERE state IN (0, 1, 2, 3)")
    List<AgentTaskEntity> recoverableAfterProcessDeath();
    @Insert(onConflict = OnConflictStrategy.ABORT) void insertEvent(AgentTaskEventEntity event);
    @Query("SELECT * FROM agent_task_event WHERE taskId = :taskId AND sequence > :afterSequence ORDER BY sequence ASC")
    List<AgentTaskEventEntity> eventsAfter(String taskId, long afterSequence);
    @Query("SELECT * FROM agent_task_operation WHERE taskId = :taskId AND clientOperationId = :operationId LIMIT 1")
    AgentTaskOperationEntity findOperation(String taskId, String operationId);
    @Insert(onConflict = OnConflictStrategy.ABORT) void insertOperation(AgentTaskOperationEntity operation);
    @Update void updateOperation(AgentTaskOperationEntity operation);
}
