package com.matrix.agent.data.embedding;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface MemoryVectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MemoryVectorEntity entity);
    @Query("SELECT * FROM memory_vector WHERE userId = :user AND zone = :zone AND layer = 'semantic' "
            + "AND epoch = :epoch AND modelVersion = :version AND dimension = :dimension "
            + "AND indexVersion = 1 ORDER BY `key` LIMIT 1024")
    List<MemoryVectorEntity> load(String user, String zone, long epoch, String version, int dimension);
}
