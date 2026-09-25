package com.matrix.agent.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * SessionHistory DAO——episodic memory 的受域持久化与召回端口。
 *
 * <p>{@code queryByUser(userId, limit)} 改为
 * {@link #queryByUserZone(String, String, int)},SQL WHERE 强制 zone 过滤——
 * 同一 Android User 不同 zone(主驾屏 / 副驾屏)的历史不能被一并召回。
 *
 * <p>{@link #queryBySession(String)} 仅为历史测试/诊断兼容保留；sessionId 不是全局主键，
 * 因而任何用户可见或业务读取都必须使用 {@link #queryByUserZone(String, String, int)}。
 */
@Dao
public interface SessionHistoryDao {
    /** Migration stubs contain no recallable facts and need no indefinite retention. */
    @Query("DELETE FROM session_history WHERE trajectoryJson = '{\"legacySanitized\":true}'")
    int deleteSanitizedLegacyRows();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SessionHistoryEntity entity);

    /**
     * 按 (userId, zone) 查询——强制访问域隔离。
     */
    @Query("SELECT * FROM session_history WHERE userId = :userId AND zone = :zone ORDER BY startedAtMillis DESC LIMIT :limit")
    List<SessionHistoryEntity> queryByUserZone(String userId, String zone, int limit);

    /** @deprecated Unscoped diagnostic query; do not use outside migration/test compatibility. */
    @Deprecated
    @Query("SELECT * FROM session_history WHERE sessionId = :sessionId")
    List<SessionHistoryEntity> queryBySession(String sessionId);

    /**
     * 按 (userId, zone) 批量删除——clearUserData 内部跨表 transaction 调用。
     */
    @Query("DELETE FROM session_history WHERE userId = :userId AND zone = :zone")
    int deleteByUserZone(String userId, String zone);

    @Query("DELETE FROM session_history WHERE userId = :userId")
    int deleteByUser(String userId);

    @Query("DELETE FROM session_history WHERE userId = :userId AND zone = :zone AND startedAtMillis < :cutoff")
    int deleteOlderThan(String userId, String zone, long cutoff);

    @Query("DELETE FROM session_history WHERE userId = :userId AND zone = :zone AND rowid NOT IN (SELECT rowid FROM session_history WHERE userId = :userId AND zone = :zone ORDER BY startedAtMillis DESC LIMIT :keep)")
    int retainLatest(String userId, String zone, int keep);

    @Query("DELETE FROM session_history WHERE userId = :userId AND zone = :zone AND sessionId = :sessionId AND startedAtMillis = :startedAtMillis")
    int deleteExact(String userId, String zone, String sessionId, long startedAtMillis);
}
