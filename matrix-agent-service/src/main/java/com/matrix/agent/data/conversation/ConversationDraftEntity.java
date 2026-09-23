package com.matrix.agent.data.conversation;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

/**
 * 会话草稿（输入交互增强 I4）：每 (owner, zone, conversation) 至多一行。
 *
 * <p>草稿是产品数据：SQLCipher 加密、随 clearUserData 级联删除、不进模型上下文/
 * 审计/自动标题。同 instance 内 revision 单调递增（last-write-wins）；提交受理的
 * 同一事务会把当前行删除并把 instance 写入 {@link ConversationConsumedDraftEntity}
 * tombstone——携带旧 instance 的迟到保存被拒绝，已发送内容不得复活。</p>
 */
@Entity(tableName = "conversation_draft",
        primaryKeys = {"owner_user_id", "vehicle_zone", "conversation_id"})
public final class ConversationDraftEntity {
    @NonNull @ColumnInfo(name = "owner_user_id")
    public String ownerUserId;

    @NonNull @ColumnInfo(name = "vehicle_zone")
    public String vehicleZone;

    @NonNull @ColumnInfo(name = "conversation_id")
    public String conversationId;

    /** 一次“从空开始编辑”的生命周期标识；新草稿 = 新 instance。 */
    @NonNull @ColumnInfo(name = "draft_instance_id")
    public String draftInstanceId;

    /** 同 instance 内单调递增；Store 只接受更大值。 */
    @ColumnInfo(name = "revision")
    public long revision;

    @NonNull @ColumnInfo(name = "text")
    public String text;

    @ColumnInfo(name = "selection_start")
    public int selectionStart;

    @ColumnInfo(name = "selection_end")
    public int selectionEnd;

    @ColumnInfo(name = "updated_at_ms")
    public long updatedAtMs;
}
