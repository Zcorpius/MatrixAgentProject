package com.matrix.agent.conversation;

import java.util.List;

/**
 * 会话草稿端口（输入交互增强 I4，§7.2）。owner/zone 由 Host 从 Binder 调用方推导，
 * Launcher 不可指定；草稿按产品数据处理（SQLCipher、clearUserData 级联、不入模型
 * 上下文/审计/自动标题）。
 */
public interface ConversationDraftStore {

    /** 草稿正文上限：12 KiB UTF-8（§7.2；超限拒绝保存并保留内存编辑值）。 */
    int MAX_TEXT_BYTES = 12 * 1024;

    /** 会话维度唯一草稿行；无草稿返回 null。 */
    DraftRow get(String ownerUserId, String vehicleZone, String conversationId);

    /**
     * 保存规则（同事务内判定）：
     * <ul>
     *   <li>同 instance 且 revision 大于库中值 → 覆盖（last-write-wins）；</li>
     *   <li>同 instance 且 revision ≤ 库中值 → 幂等 no-op（乱序迟到，新值已在）；</li>
     *   <li>新 instance → 替换（新草稿生命周期）；</li>
     *   <li>instance 已在 tombstone → 拒绝（{@link SaveResult#REJECTED_CONSUMED_INSTANCE}），
     *       已发送内容不得复活；</li>
     *   <li>正文超 12 KiB 或 selection 非法 → 拒绝（保留内存编辑值，客户端提示缩短）。</li>
     * </ul>
     */
    SaveResult save(SaveCommand command);

    /** 显式丢弃：仅当行仍为该 instance 时删除并 tombstone 该 instance；其余 no-op。 */
    void discard(String ownerUserId, String vehicleZone, String conversationId,
            String draftInstanceId);

    /**
     * 在调用方已开启的 Room 事务线程内消费<strong>本次提交快照</strong>的 instance。
     * 即使草稿行尚未落库，也必须先写 tombstone；这样晚到的 saveDraft 无法把已发送
     * 内容复活。若库中已是一个新 instance，则绝不删除它。由
     * {@code RoomConversationStore} 的提交事务调用，不得自行开事务。
     */
    void consumeSubmittedInCallerTransaction(String ownerUserId, String vehicleZone,
            String conversationId, String draftInstanceId);

    /**
     * Legacy test/helper convenience only. Production submissions must call
     * {@link #consumeSubmittedInCallerTransaction(String, String, String, String)} with their
     * immutable submit snapshot, never infer it from the mutable current row.
     */
    @Deprecated
    default void consumeCurrentInCallerTransaction(String ownerUserId, String vehicleZone,
            String conversationId) {
        DraftRow current = get(ownerUserId, vehicleZone, conversationId);
        if (current != null) {
            consumeSubmittedInCallerTransaction(ownerUserId, vehicleZone, conversationId,
                    current.draftInstanceId());
        }
    }

    /** clearUserData / 会话删除覆盖：草稿与 tombstone 按 owner 全删。 */
    void clearForUsers(List<String> userIds);

    /**
     * tombstone 清理谓词：同一会话最多保留 128 条，且任何条目最多保留 7 天。提交端
     * 已由 Launcher keyed lane 全序化；这两个保守窗口之外不再存在合法的在途保存，
     * 因而可回收以避免低频会话永久累积 tombstone。返回删除条数。
     */
    int cleanupTombstones(long nowMs);

    enum SaveResult {
        SAVED, IDEMPOTENT_STALE_REVISION, REJECTED_CONSUMED_INSTANCE, REJECTED_PAYLOAD
    }

    record DraftRow(String conversationId, String draftInstanceId, long revision,
            String text, int selectionStart, int selectionEnd, long updatedAtMs) { }

    record SaveCommand(String ownerUserId, String vehicleZone, String conversationId,
            String draftInstanceId, long revision, String text, int selectionStart,
            int selectionEnd, long updatedAtMs) { }
}
