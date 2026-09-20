package com.matrix.agent.contract;

import java.util.List;

/**
 * 跨任务对话历史的“种子”契约：由 task 域的 ConversationContextAssembler 在
 * keyed lane 出队时装配（前序任务已终态，不会遗漏上一轮结果），经
 * {@code AgentRequest.Builder.conversationSeed()} 注入；AgentEngine 在当前 user
 * 消息之前把它写入模型 conversation。
 *
 * <p>归属说明：本类型位于 contract 而非 task——identity.AgentRequest 是架构边界测试
 * 保护的业务叶子，不得依赖 task 实现；种子是“请求可携带的中立上下文”，与
 * AgentMessage 同属提供方无关契约。</p>
 *
 * <p>内容契约：进入本对象的每条消息都已经过 ModelSanitizer 凭据清洗、字符预算裁剪，
 * 且只包含 COMPLETED 的 user/assistant 对——FAILED/REJECTED 的技术细节与原始错误
 * 由装配器过滤，绝不进入模型上下文。Engine 只做防御性单条上限与总量兜底，
 * 不重复实施装配规则。</p>
 */
public record ConversationSeedContext(List<AgentMessage> messages) {

    public ConversationSeedContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }
}
