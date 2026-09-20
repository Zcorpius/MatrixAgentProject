package com.matrix.agent.api.conversation;

import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.api.conversation.ConversationListQuery;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationOperationResult;
import com.matrix.agent.api.conversation.ConversationPage;
import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.api.conversation.CreateConversationRequest;
import com.matrix.agent.api.conversation.IConversationCallback;
import com.matrix.agent.api.conversation.SendTextRequest;

/**
 * 对话域 Binder：用户可见的对话线程、消息历史与文字提交。
 *
 * <p>音频永远不经本接口——PTT/唤醒仍由 IVoiceService 会话产出最终文本后在 Host 内部
 * 进入同一协调器；本接口只承载受限文本、状态与分页。所有方法在 Host 恢复对账完成前
 * fail-closed（空结果或 SERVICE_NOT_READY），不返回半套对话状态。</p>
 */
interface IConversationService {
    ConversationInfo createConversation(in CreateConversationRequest request,
            String clientOperationId);

    List<ConversationInfo> listConversations(in ConversationListQuery query);

    /** 分页读取消息；beforeSequenceExclusive 为 -1 时从最新一条向前取 limit 条，
     *  返回页内按 sequence 升序排列。 */
    ConversationPage getMessages(String conversationId, long beforeSequenceExclusive, int limit);

    /** 向后翻页：sequence 严格大于 afterSequenceExclusive，升序取 limit 条。 */
    ConversationPage getMessagesAfter(String conversationId, long afterSequenceExclusive,
            int limit);

    /** 锚点定位窗口：围绕 anchorSequence 取 limit 条；
     *  锚点被清理/越权/不存在时统一返回空页且 anchorExists=false，不泄漏存在性。 */
    ConversationPage getMessagesAround(String conversationId, long anchorSequence, int limit);

    /** 用户重命名：titleOrigin 置 USER，此后自动标题永不覆盖。 */
    ConversationOperationResult renameConversation(String conversationId, String title,
            String clientOperationId);

    /** 提交一条文字消息并调度对应 Agent 任务；返回被持久化的用户消息投影。
     *  相同 clientOperationId（同 conversation）重放返回既有消息，不二次执行。 */
    ConversationSubmission sendText(in SendTextRequest request, String clientOperationId);

    /** REPROMPT 追加到当前 RUNNING 任务（steer）；无 RUNNING 任务时返回 INVALID_STATE。
     *  与 sendText（新任务）语义互斥。 */
    ConversationOperationResult appendMessage(String conversationId, String text,
            String clientOperationId);

    ConversationOperationResult cancelMessage(String conversationId, String messageId,
            String clientOperationId);

    /**
     * 创建一次性 PTT 绑定：返回高熵 bindingOperationId（小写 UUID），
     * 调用方将其原样作为 IVoiceService.startUserInitiatedSession 的 clientOperationId。
     * 30 秒有效，仅可消费一次（设计文档 §8.1）。
     */
    String createVoiceBinding(String conversationId, String clientOperationId);

    /** 收藏/个人备注合并 upsert（评估 v1.0 §4.4）：不改消息本体、不入模型上下文。 */
    ConversationOperationResult annotateMessage(String conversationId, String messageId,
            boolean favorite, String userNote, String clientOperationId);

    /** 快照式安全分支（评估 v1.0 §4.5）：在已完成消息处切出子会话；返回子会话 id。 */
    String forkConversation(String parentConversationId, long atSequenceNo,
            String clientOperationId);

    /** 子会话的来源说明（可空：非分支会话或父已清除）。 */
    String getLineageSummary(String childConversationId);

    /** 只读导出为 Markdown：返回受授予 content URI（FileProvider）；失败/降级返回 null。 */
    String exportConversation(String conversationId, String clientOperationId);

    void subscribeConversation(String conversationId, in IConversationCallback callback);

    void unsubscribeConversation(in IConversationCallback callback);
}
