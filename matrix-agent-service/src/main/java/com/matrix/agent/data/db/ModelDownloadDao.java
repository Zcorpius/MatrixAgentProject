package com.matrix.agent.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 端侧模型下载管理——ModelDownloadEntity 的 Room DAO。
 */
@Dao
public interface ModelDownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ModelDownloadEntity entity);

    @Update
    void update(ModelDownloadEntity entity);

    @Query("SELECT * FROM model_download ORDER BY updatedAt DESC")
    List<ModelDownloadEntity> getAll();

    @Query("SELECT * FROM model_download WHERE status = 'COMPLETED'")
    List<ModelDownloadEntity> getCompleted();

    @Query("SELECT * FROM model_download WHERE modelName = :modelName LIMIT 1")
    ModelDownloadEntity getByName(String modelName);

    @Query("DELETE FROM model_download WHERE modelName = :modelName")
    void deleteByName(String modelName);
}
