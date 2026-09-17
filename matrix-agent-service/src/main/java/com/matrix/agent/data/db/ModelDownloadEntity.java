package com.matrix.agent.data.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 端侧模型下载管理——模型下载记录 Room entity。
 *
 * <p>每个模型一行,记录下载进度/状态/校验信息。modelName 既是主键,也是
 * {@code filesDir/models/mnn/<modelName>/} 的目录名。
 *
 * <p>status 取值:{@code IDLE / DOWNLOADING / PAUSED / COMPLETED / FAILED}。
 */
@Entity(tableName = "model_download")
public final class ModelDownloadEntity {
    @PrimaryKey
    @androidx.annotation.NonNull
    public String modelName;        // 模型名（如 Qwen3-0.6B-MNN），也是 filesDir/models/mnn/<modelName>/ 的目录名
    public String displayName;      // 显示名
    public String sourceRepo;       // ModelScope owner/repo（如 MNN/Qwen3-0.6B-MNN）
    public String marketUrl;        // 模型市场 JSON URL（可选）
    public long totalBytes;         // 总大小（字节）
    public long downloadedBytes;    // 已下载字节
    public String status;           // IDLE/DOWNLOADING/PAUSED/COMPLETED/FAILED
    public String sha256;           // SHA-256（可选，用于校验）
    public long createdAt;          // 创建时间（System.currentTimeMillis）
    public long updatedAt;          // 更新时间

    public ModelDownloadEntity() {} // Room 需要
}
