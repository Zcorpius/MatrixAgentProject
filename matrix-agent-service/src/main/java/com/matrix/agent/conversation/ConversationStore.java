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

    /**
     * 定向窗口（评估 v1.0 §4.2）：messagesAscending 为窗口内容；
     * hasBefore/hasAfter 表示窗口外两侧是否还有更早/更新消息；
     * anchorExists=false 时 messages 恒空（越权/被清理/不存在统一不可定位，不泄漏存在性）。
     */
    MessageWindow windowAfter(String conversationId, long afterSequenceExclusive, int limit);

    MessageWindow windowAround(String conversationId, long anchorSequence, int limit);

    record MessageWindow(List<MessageRow> messagesAscending, boolean hasBefore,
            boolean hasAfter, boolean anchorExists) {

        public static MessageWindow anchorMissing() {
            return new MessageWindow(List.of(), false, false, false);
        }
    }

    /**
     * 用户重命名（评估 v1.0 §4.1）：无条件把 titleOrigin 置 USER——此后 AUTO 永不覆盖。
     * 会话不存在返回 false。
     */
    boolean renameConversation(String conversationId, String title);

    /**
     * 自动标题比较交换：仅当 titleOrigin 仍为 DEFAULT 时写入并置 AUTO，
     * 任何其他来源（USER / 既有 AUTO）永不覆盖。返回是否实际写入。
     */
    boolean autoTitleIfDefault(String conversationId, String title);

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
            String runtimeRequestId, boolean readOnlyHint, String idempotencyKey,
            String submittedDraftInstanceId, String quotedMessageId, String quoteSnapshot,
            List<String> contextAttachmentIds) {
        /** Source-compatible constructor for inputs that do not originate from a text draft. */
        public UserSubmission(String conversationId, String messageId, int channelWire,
                String text, String languageTag, String conversationTaskId,
                String runtimeRequestId, boolean readOnlyHint, String idempotencyKey) {
            this(conversationId, messageId, channelWire, text, languageTag, conversationTaskId,
                    runtimeRequestId, readOnlyHint, idempotencyKey, null, null, null, null);
        }

        /** Source-compatible constructor for draft-aware primary input without a quote. */
        public UserSubmission(String conversationId, String messageId, int channelWire,
                String text, String languageTag, String conversationTaskId,
                String runtimeRequestId, boolean readOnlyHint, String idempotencyKey,
                String submittedDraftInstanceId) {
            this(conversationId, messageId, channelWire, text, languageTag, conversationTaskId,
                    runtimeRequestId, readOnlyHint, idempotencyKey, submittedDraftInstanceId,
                    null, null, null);
        }

        /** Source-compatible constructor for quote-bearing input without attachments. */
        public UserSubmission(String conversationId, String messageId, int channelWire,
                String text, String languageTag, String conversationTaskId,
                String runtimeRequestId, boolean readOnlyHint, String idempotencyKey,
                String submittedDraftInstanceId, String quotedMessageId, String quoteSnapshot) {
            this(conversationId, messageId, channelWire, text, languageTag, conversationTaskId,
                    runtimeRequestId, readOnlyHint, idempotencyKey, submittedDraftInstanceId,
                    quotedMessageId, quoteSnapshot, List.of());
        }
    }

    record SubmittedUserMessage(long sequenceNo, boolean replay) { }

    // ---- steer 附属输入（评估 v1.0 §4.3；仅 REPROMPT，FORCE_TOOL/DEFER 不走此路径） ----

    /**
     * 原子追加一条 INPUT_STEER 用户消息（无 task link、无独立终态）。同一事务内：
     * 幂等键命中返回 {@code replay=true} 不重复插入；校验会话存在/未归档且宿主任务
     * 仍运行中（运行 link 的 userMessageId 必须等于 hostUserMessageId）——宿主终态
     * 写入与本插入经 DB 写锁互斥，杜绝“宿主已终态却插入显示 RUNNING 的 steer”。
     * 宿主无运行任务时抛 {@link IllegalStateException}。
     */
    SubmittedSteerMessage appendSteerMessage(SteerSubmission command);

    record SteerSubmission(String conversationId, String messageId, int channelWire,
            String text, String languageTag, String hostUserMessageId,
            String idempotencyKey, String submittedDraftInstanceId,
            List<String> contextAttachmentIds) {
        /** Source-compatible constructor for voice and legacy append callers. */
        public SteerSubmission(String conversationId, String messageId, int channelWire,
                String text, String languageTag, String hostUserMessageId,
                String idempotencyKey) {
            this(conversationId, messageId, channelWire, text, languageTag, hostUserMessageId,
                    idempotencyKey, null, List.of());
        }

        public SteerSubmission(String conversationId, String messageId, int channelWire,
                String text, String languageTag, String hostUserMessageId,
                String idempotencyKey, String submittedDraftInstanceId) {
            this(conversationId, messageId, channelWire, text, languageTag, hostUserMessageId,
                    idempotencyKey, submittedDraftInstanceId, List.of());
        }
    }

    record SubmittedSteerMessage(long sequenceNo, boolean replay) { }

    /**
     * 投递态推进：offered=true → PENDING 变 OFFERED；false → PENDING 变 FAILED 且
     * 消息状态收敛 FAILED（“未能并入宿主请求”）。仅 PENDING 行生效，其余 no-op
     * （迟到回执不得覆盖已收敛事实）。
     */
    void updateSteerDelivery(String messageId, boolean offered, String submittedDraftInstanceId);

    /** Compatibility convenience for legacy/voice callers with no Launcher draft snapshot. */
    default void updateSteerDelivery(String messageId, boolean offered) {
        updateSteerDelivery(messageId, offered, null);
    }

    // ---- 执行期状态机 ----

    /** 出队时置 RUNNING（消息 + link.started_at）；任务不存在返回 false。 */
    boolean markRunning(String conversationTaskId);

    /**
     * 终态事务：用户消息置终态 + link 终态（assistantMessageId/terminalAtMs）+
     * 插入 assistant 消息（assistantText 非空时）+ **同一事务内把仍为 RUNNING 的
     * 附属 steer 行（steer_host_user_message_id 指向本宿主）镜像收敛为同终态**
     * （投递态 FAILED 的 steer 已自收敛，不被覆盖）。conversation 已被 clear 的行
     * 静默丢弃（返回 dropped）——清库后旧异步任务不得回写（epoch 等价门，§5.2）。
     *
     * <p>返回 {@link TerminalOutcome}：除写入结果外，携带本事务实际收敛的 steer 行
     * 事实（messageId + 终态 + 错误码）。调用方（Coordinator）据此为每行补发
     * upsert/status 事件——宿主终态后 UI 不得停留在“正在并入”（评审 P1：镜像收敛
     * 若无事件投影，客户端缓存将悬挂至重进会话）。恢复对账（writeRecoveryOutcome）
     * 不需要该返回值：对账完成先于任何订阅，快照读取的已是收敛后事实。</p>
     */
    TerminalOutcome writeTerminal(TerminalWrite command);

    record TerminalWrite(String conversationTaskId, int userStatusWire, int failureCode,
            String assistantMessageId, String assistantText, String traceJson) {

        /** 源码兼容：无轨迹的终态（异常兜底/无工具调用）。 */
        public TerminalWrite(String conversationTaskId, int userStatusWire, int failureCode,
                String assistantMessageId, String assistantText) {
            this(conversationTaskId, userStatusWire, failureCode, assistantMessageId,
                    assistantText, null);
        }
    }

    /** writeTerminal 的结果：written=false 表示会话已清除/任务已终态（幂等丢弃）。 */
    record TerminalOutcome(boolean written, List<ConvergedSteer> convergedSteers) {

        public static TerminalOutcome dropped() {
            return new TerminalOutcome(false, List.of());
        }

        public static TerminalOutcome writtenWith(List<ConvergedSteer> convergedSteers) {
            return new TerminalOutcome(true, convergedSteers);
        }
    }

    /** 同事务镜像收敛的一行附属输入事实；供调用方补发事件，不再回查。 */
    record ConvergedSteer(String messageId, int statusWire, int failureCode) { }

    /** 恢复对账：写入一条 sequence 有序的 SYSTEM 说明行；返回其 sequence。 */
    long appendSystemNote(String conversationId, String text);

    // ---- 用户组织（评估 v1.0 阶段 3：标注 / 引用 / 分支谱系） ----

    /** 标注 upsert（收藏/备注合并写）；消息不存在抛 IllegalArgumentException。 */
    void upsertAnnotation(AnnotationUpsert command);

    record AnnotationUpsert(String messageId, String ownerUserId, boolean favorite,
            String userNote) { }

    /** 可空。 */
    AnnotationRow findAnnotation(String messageId, String ownerUserId);

    record AnnotationRow(String messageId, String ownerUserId, boolean favorite,
            String userNote, long updatedAtMs) { }

    /** 可空。 */
    QuoteRow findQuoteByQuotingMessage(String messageId);

    record QuoteRow(String messageId, String quotedMessageId, String snapshot) { }

    /**
     * 分支谱系落库：同事务创建子会话行 + lineage（快照由调用方经同一装配器输入形态
     * 生成）。父会话不存在抛 IllegalArgumentException。
     */
    void recordLineage(LineageRecord command);

    record LineageRecord(String childConversationId, String parentConversationId,
            long forkSequenceNo, String parentTitleAtFork, String seedSnapshotJson,
            String createdByUser) { }

    /** 可空：无谱系（非分支会话）。父删除后 parentConversationId 为 null。 */
    LineageRow findLineage(String childConversationId);

    record LineageRow(String childConversationId, String parentConversationId,
            long forkSequenceNo, String parentTitleAtFork, String seedSnapshotJson,
            int seedVersion) { }

    // ---- 恢复 / 清理 ----

    /** 恢复对账的扫描集：一切未终态 link（terminal_status IS NULL）。 */
    List<InterruptedLink> loadNonTerminalLinks();

    record InterruptedLink(String conversationTaskId, String conversationId,
            String userMessageId, boolean readOnlyHint) { }

    /** 对账终态：用户消息终态 + link 终态；readOnly=false 侧由调用方传 EXECUTION_UNKNOWN。 */
    void writeRecoveryOutcome(String conversationTaskId, int userStatusWire, int failureCode);

    /** 会话活动 touch（最近使用排序；不写消息）。 */
    void touchActivity(String conversationId);

    /** 可空：任务链接的轨迹投影 JSON（无任务/未投影）。 */
    String findTraceJson(String conversationTaskId);

    /** 可空：无运行中任务。 */
    String findRunningTaskId(String conversationId);

    /** 可空：运行中宿主任务的用户消息 id（steer 附属输入的挂载点）。 */
    String findRunningUserMessageId(String conversationId);

    /** clearUserData 覆盖：按 owner 级联删除线程/消息/关联。返回删除的线程数。 */
    int clearForUsers(List<String> userIds);

    // ---- 行投影 ----

    record ConversationRow(String conversationId, String ownerUserId, String vehicleZone,
            String title, boolean archived, long createdAtMs, long updatedAtMs,
            int titleOrigin, boolean pinned, int lastInputChannel) { }

    record MessageRow(String messageId, String conversationId, long sequenceNo, int roleWire,
            int statusWire, int channelWire, String text, String languageTag,
            String conversationTaskId, int failureCode, long createdAtMs, long updatedAtMs,
            int inputKindWire, String steerHostUserMessageId, int steerDeliveryWire) {

        public static final int CHANNEL_NONE_WIRE = 0;

        /** ConversationMessage.INPUT_* 投影。 */
        public static final int INPUT_PRIMARY_WIRE = 0;
        public static final int INPUT_STEER_WIRE = 1;

        /** ConversationMessage.STEER_DELIVERY_* 投影；非 steer 行恒为 NONE。 */
        public static final int STEER_DELIVERY_NONE_WIRE = -1;
    }
}
