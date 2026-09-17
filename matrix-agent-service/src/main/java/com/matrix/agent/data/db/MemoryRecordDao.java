package com.matrix.agent.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * MemoryRecord DAO——RoomMemoryStore / RoomMemoryWriter 的持久化端口。
 *
 * <p>查询接口必须以 (userId, zone) 作访问域,
 * 避免同一 Android User 不同 zone 的记忆被一并取出。原 {@code queryByUserLayer(userId, layer)}
 * 改为 {@link #queryByUserZoneLayer(String, String, String)},SQL WHERE 强制 zone 过滤。
 *
 * <p>{@link #deleteByUser(String)} 保留按 userId 全量清理语义——用于账号切换 / 用户擦除场景
 * (合法的全量清空,不是按 zone 的细粒度召回);引入 forget(userId, zone) 时再补细粒度删除。
 */
@Dao
public interface MemoryRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(MemoryRecordEntity entity);

    /**
     * 按 (userId, zone, layer) 查询——强制访问域隔离。
     *
     * <p>zone 必传,SQL 不再退化为 userId-only。召回算法接入时直接调此方法,
     * 不允许调用方在 caller 侧手动过滤 zone。
     */
    @Query("SELECT * FROM memory_record WHERE userId = :userId AND zone = :zone AND layer = :layer")
    List<MemoryRecordEntity> queryByUserZoneLayer(String userId, String zone, String layer);

    @Query("SELECT * FROM memory_record WHERE userId = :userId AND zone = :zone AND layer = :layer AND key = :key LIMIT 1")
    MemoryRecordEntity queryByKey(String userId, String zone, String layer, String key);

    @Query("DELETE FROM memory_record WHERE userId = :userId")
    int deleteByUser(String userId);

    /**
     * 按 (userId, zone) 批量删除——clearUserData 内部跨表 transaction 调用。
     *
     * <p>与 {@link #deleteByUser(String)} 区别:本方法只清当前 user/zone 的偏好,
     * 同 user 不同 zone 的偏好保留(账号切换 / 用户擦除场景仍用 {@link #deleteByUser})。
     */
    @Query("DELETE FROM memory_record WHERE userId = :userId AND zone = :zone")
    int deleteByUserZone(String userId, String zone);
}
