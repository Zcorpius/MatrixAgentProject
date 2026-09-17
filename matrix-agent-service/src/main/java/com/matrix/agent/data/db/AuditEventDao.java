package com.matrix.agent.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * AuditEvent DAO——{@code AuditEventRecorder} 的增量事件持久化端口。
 *
 * <p>加 deleteByUserZone / queryByUserZone——
 * clearByUserZone 跨 4 表删除依赖此方法。
 */
@Dao
public interface AuditEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(AuditEventEntity entity);

    @Query("SELECT * FROM audit_event WHERE requestId = :requestId ORDER BY happenedAtMs ASC")
    List<AuditEventEntity> queryByRequest(String requestId);

    /**
     * 按 (userId, zone) 查询——audit_event 主路径查询接口。
     * 历史行 userId='' 默认过滤不到。
     */
    @Query("SELECT * FROM audit_event WHERE userId = :userId AND zone = :zone "
            + "ORDER BY happenedAtMs ASC")
    List<AuditEventEntity> queryByUserZone(String userId, String zone);

    /**
     * 按 (userId, zone) 删除——clearByUserZone 4 表原子删除入口。
     * 返回删除行数(Room 透明返回)。
     */
    @Query("DELETE FROM audit_event WHERE userId = :userId AND zone = :zone")
    int deleteByUserZone(String userId, String zone);
}
