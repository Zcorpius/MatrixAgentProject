package com.matrix.agent.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 细粒度 Audit 事件的 SQLCipher 持久化记录。
 *
 * <p>{@code AuditEventRecorder} 异步写入 PRE_TOOL / POST_TOOL / POLICY / STEER 等
 * 增量事件；终态的完整 trajectory 仍由 {@code RoomAuditRepository} 独立持久化。两者
 * 承担不同查询需求，不能把其中任一视为另一个的冗余副本。
 *
 * <p><b>加 userId 列</b>——schema v2。clearByUserZone 跨 4 表原子
 * 删除(audit_event 也按 userId+zone 清)依赖此列;queryByUserZone 也依赖此列。
 *
 * <p>历史行 userId 默认 {@code ""}(空串)——无法回填
 * (无 RequestId → AgentRequest 映射),按主路径查询过滤不到,但仍按 requestId 可查。
 *
 */
@Entity(tableName = "audit_event",
        indices = {
                @Index(value = {"requestId"}, name = "idx_audit_request"),
                @Index(value = {"userId", "zone"}, name = "idx_audit_user_zone")
        })
public final class AuditEventEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String requestId;

    /** 事件类型:初始仅 TERMINAL;后续加 PRE_TOOL/POST_TOOL/POLICY/STEER。 */
    public String type;

    public String actor;
    public String zone;
    public long happenedAtMs;
    public String payloadJson;

    /**
     * 用户维度——按 userId 清理 / 查询 audit 事件。
     * 历史行 userId 默认 {@code ""}(Migration v1→v2 ALTER DEFAULT '')。
     */
    @NonNull
    public String userId = "";

    /**
     * AgentRequest.epoch——任务启动时捕获的 MemoryStore 版本号。
     *
     * <p>clearUserDataDetailed 推进 epoch 后,AuditEventRecorder 用此字段判断事件是否 stale:
     * entity.requestEpoch < currentEpoch → drop(不入队 / 不 insert)。
     *
     * <p>历史行 requestEpoch 默认 0(Migration v2→v3 ALTER DEFAULT 0)——
     * 0 表示"未透传 / 老数据",不参与 epoch gate(保留原行为)。
     */
    public long requestEpoch = 0L;
}
