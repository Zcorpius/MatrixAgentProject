package com.matrix.agent.data.conversation;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * 安全分支谱系（评估 v1.0 §4.5/§5.2）：子会话指向父会话与切点，并物化切点处的
 * 种子快照（{@code ConversationHistorySource.HistoryEntry} 列表 JSON——同一装配器
 * 的输入形态，绝不另写裁剪规则）。
 *
 * <p>级联语义：子会话删除 → 本行 CASCADE 消失；父会话删除 → parent 置 NULL，
 * 已物化的快照让子会话自洽续聊，来源说明回退到 {@code parentTitleAtFork} 快照
 * （“来源已清除”）。父会话后续新增内容不影响分支（快照冻结）。</p>
 */
@Entity(tableName = "conversation_lineage",
        primaryKeys = {"child_conversation_id"},
        indices = {
                @Index(value = {"parent_conversation_id"}, name = "idx_lineage_parent"),
        },
        foreignKeys = {
                @ForeignKey(entity = ConversationEntity.class,
                        parentColumns = "conversation_id",
                        childColumns = "child_conversation_id",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = ConversationEntity.class,
                        parentColumns = "conversation_id",
                        childColumns = "parent_conversation_id",
                        onDelete = ForeignKey.SET_NULL),
        })
public final class ConversationLineageEntity {

    /** 子会话（分支本体）。 */
    @androidx.annotation.NonNull
    @ColumnInfo(name = "child_conversation_id")
    public String childConversationId;

    /** 父会话；父删除后为 NULL（快照自洽）。 */
    @ColumnInfo(name = "parent_conversation_id")
    public String parentConversationId;

    /** 切点：父会话内已完成消息的 sequenceNo（含）。 */
    @ColumnInfo(name = "fork_sequence_no")
    public long forkSequenceNo;

    /** 分支时刻的父标题快照（来源说明；父删除后仍可显示）。可空。 */
    @ColumnInfo(name = "parent_title_at_fork")
    public String parentTitleAtFork;

    /**
     * 种子快照：HistoryEntry 列表 JSON（切点前的已完成回合，装配器输入形态）。
     * 子会话历史 = 快照 + 子会话自身消息，同一 ConversationContextAssembler
     * 统一按预算装配。
     */
    @androidx.annotation.NonNull
    @ColumnInfo(name = "seed_snapshot")
    public String seedSnapshot;

    /** 快照格式版本（当前 1；未来格式演进时递增）。 */
    @ColumnInfo(name = "seed_version")
    public int seedVersion;

    @androidx.annotation.NonNull
    @ColumnInfo(name = "created_by_user")
    public String createdByUser;

    @ColumnInfo(name = "created_at_ms")
    public long createdAtMs;
}
