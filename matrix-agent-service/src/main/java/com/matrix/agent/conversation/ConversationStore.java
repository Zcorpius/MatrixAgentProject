package com.matrix.agent.conversation;

import java.util.List;
import java.util.Optional;

/**
 * 对话持久化端口（领域视角的唯一读写面）。实现（conversation/persistence/RoomConversationStore）
 * 把命令投影到 Room 事务；“用户消息 + task link”的原子提交与 sequence 分配是端口的
 * 事务性契约，不信任调用方自行拼装（设计文档 §4.3-4/§5.2）。
 */
public interface ConversationStore {

    // ---- 线程 ----

    ConversationRow createConversation(NewConversation command);

    record NewConversation(String conversationId, String ownerUserId, String vehicleZone,
            String title, int schemaVersion) { }

    /** 可空。 */
    ConversationRow findConversation(String conversationId);

    List<ConversationRow> listConversations(String ownerUserId, boolean includeArchived,
            int limit);

    // ---- 消息读取 ----

    /** 可空。 */
    MessageRow findMessage(String messageId);

    /** 幂等命中行查找（重放返回既有事实）。 */
    MessageRow findMessageByIdempotencyKey(String idempotencyKey);

    /** 最新 limit 条，sequence 升序（订阅快照）。 */
    List<MessageRow> latestMessages(String conversationId, int limit);

    /** 种子装配输入：仅 COMPLETED 的 user/assistant 文本，升序，≤maxEntries 条。 */
    List<MessageRow> latestCompletedForSeed(String conversationId, int maxEntries);

    /** 向前翻页：sequence 严格小于 beforeSequenceExclusive，升序返回；limit 由调用方裁剪。 */
    MessagePage pageMessages(String conversationId, long beforeSequenceExclusive, int limit);

    record MessagePage(List<MessageRow> messagesAscending, boolean hasMore) { }

    // ---- 提交（原子事务：幂等检查 + sequence 分配 + 消息 + task link） ----

    /**
     * 同一 DB 事务内：按 idempotencyKey 查重（命中返回既有行且 {@code replay=true}）；
     * 分配 sequence（MAX+1，事务写锁串行化并发提交）；插入 USER/ACCEPTED 消息与
     * task link（runtime_request_id + read_only_hint 快照）。会话不存在/已归档时抛
     * {@link IllegalArgumentException}，不落任何行。
     */
    SubmittedUserMessage submitUserMessage(UserSubmission command);

    record UserSubmission(String conversationId, String messageId, int channelWire,
            String text, String languageTag, String conversationTaskId,
            String runtimeRequestId, boolean readOnlyHint, String idempotencyKey) { }

    record SubmittedUserMessage(long sequenceNo, boolean replay) { }

    // ---- 执行期状态机 ----

    /** 出队时置 RUNNING（消息 + link.started_at）；任务不存在返回 false。 */
    boolean markRunning(String conversationTaskId);

    /**
     * 终态事务：用户消息置终态 + link 终态（assistantMessageId/terminalAtMs）+
     * 插入 assistant 消息（assistantText 非空时）。conversation 已被 clear 的行
     * 静默丢弃（返回 false）——清库后旧异步任务不得回写（epoch 等价门，§5.2）。
     */
    boolean writeTerminal(TerminalWrite command);

    record TerminalWrite(String conversationTaskId, int userStatusWire, int failureCode,
            String assistantMessageId, String assistantText) { }

    /** 恢复对账：写入一条 sequence 有序的 SYSTEM 说明行；返回其 sequence。 */
    long appendSystemNote(String conversationId, String text);

    // ---- 恢复 / 清理 ----

    /** 恢复对账的扫描集：一切未终态 link（terminal_status IS NULL）。 */
    List<InterruptedLink> loadNonTerminalLinks();

    record InterruptedLink(String conversationTaskId, String conversationId,
            String userMessageId, boolean readOnlyHint) { }

    /** 对账终态：用户消息终态 + link 终态；readOnly=false 侧由调用方传 EXECUTION_UNKNOWN。 */
    void writeRecoveryOutcome(String conversationTaskId, int userStatusWire, int failureCode);

    /** 可空：无运行中任务。 */
    String findRunningTaskId(String conversationId);

    /** clearUserData 覆盖：按 owner 级联删除线程/消息/关联。返回删除的线程数。 */
    int clearForUsers(List<String> userIds);

    // ---- 行投影 ----

    record ConversationRow(String conversationId, String ownerUserId, String vehicleZone,
            String title, boolean archived, long createdAtMs, long updatedAtMs) { }

    record MessageRow(String messageId, String conversationId, long sequenceNo, int roleWire,
            int statusWire, int channelWire, String text, String languageTag,
            String conversationTaskId, int failureCode, long createdAtMs, long updatedAtMs) {

        public static final int CHANNEL_NONE_WIRE = 0;
    }
}
