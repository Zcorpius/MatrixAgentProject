package com.matrix.agent.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Trajectory DAO——AgentEngine 5 个出口点的 Audit 落库入口。
 *
 * <p>所有方法同步调用,RoomAuditRepository 内部直接调用(已移除 Executor)。
 *
 * <p>{@code queryBySession} 改 {@link #queryBySessionScoped},
 * SQL WHERE 强制 (userId, zone) 访问域。
 *
 * <p>{@code queryByRequest(String)} 改 {@link #queryByRequestScoped},
 * SQL WHERE 强制 userId + zone + requestId 三元组——UUID 全局唯一不能替代权限校验,
 * 防止任何 caller 凭枚举 requestId 越权读他人审计。
 */
@Dao
public interface TrajectoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(TrajectoryEntity entity);

    /**
     * 按 (userId, zone, requestId) 查询单条记录,
     * SQL WHERE 强制 userId + zone 双重过滤,跨用户 / 跨 zone 查询返回 null。
     */
    @Query("SELECT * FROM trajectory WHERE userId = :userId AND zone = :zone AND requestId = :requestId LIMIT 1")
    TrajectoryEntity queryByRequestScoped(String userId, String zone, String requestId);

    /**
     * 按 (userId, zone, sessionId) 查询——强制访问域隔离。
     *
     * <p>SQL WHERE 强制 userId + zone + sessionId 三元组,调用方必须显式传入访问范围;
     * UI 回放层不能仅凭 sessionId 取记录,必须先校验当前用户/zone。
     */
    @Query("SELECT * FROM trajectory WHERE userId = :userId AND zone = :zone AND sessionId = :sessionId ORDER BY startedMs DESC LIMIT :limit")
    List<TrajectoryEntity> queryBySessionScoped(String userId, String zone, String sessionId, int limit);

    /**
     * 按 (userId, zone) 批量删除——clearUserData 内部 4 表跨表 transaction 调用。
     *
     * <p>SQL WHERE 强制 userId + zone 双重过滤:只清当前 user/zone 的轨迹,
     * 其他 user 同 zone、同 user 不同 zone 的行不受影响。
     */
    @Query("DELETE FROM trajectory WHERE userId = :userId AND zone = :zone")
    int deleteByUserZone(String userId, String zone);
}
