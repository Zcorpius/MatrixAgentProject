package com.matrix.agent.data.failure;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface FailureLessonDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) long insert(FailureLessonEntity row);
    @Query("SELECT * FROM failure_lesson WHERE owner=:owner AND zone=:zone AND epoch=:epoch "
            + "AND capability IN (:capabilities) AND expiresAt>:now ORDER BY createdAt DESC, sourceTask ASC LIMIT :limit")
    List<FailureLessonEntity> recall(String owner, String zone, long epoch,
            List<String> capabilities, long now, int limit);
    @Query("DELETE FROM failure_lesson WHERE expiresAt<=:now OR epoch!=:epoch")
    void prune(long now, long epoch);
    @Query("DELETE FROM failure_lesson WHERE owner=:owner AND zone=:zone AND sourceTask NOT IN "
            + "(SELECT sourceTask FROM failure_lesson WHERE owner=:owner AND zone=:zone "
            + "ORDER BY createdAt DESC, sourceTask ASC LIMIT 100)")
    void trim(String owner, String zone);
    @Query("DELETE FROM failure_lesson WHERE owner=:owner AND zone=:zone")
    void delete(String owner, String zone);
    @Query("DELETE FROM failure_lesson WHERE owner=:owner") void deleteOwner(String owner);
}
