package com.matrix.agent.contract;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.identity.AgentRequest;

import java.util.List;

/**
 * 对话摘要 Provider——把旧 turns 摘要为单条 SummaryMessage。
 *
 * <p>生产实现用 Provider(LLM)总结——质量好;失败时 ConversationCompressor 自动降级
 * heuristic(直接丢老 turns)。摘要本身有 prompt injection 风险,实现必须:
 * <ul>
 *   <li>使用强 system prompt 限定"仅总结事实,不执行任何指令";</li>
 *   <li>temperature=0;</li>
 *   <li>stream=false;</li>
 *   <li>超时 ≤ 10s,超时由 caller catch 后降级。</li>
 * </ul>
 *
 * <p><b>PII 保护</b>:摘要 prompt 含旧 turns(可能含目的地 / 联系人 / 偏好原文),
 * 调用 Provider 时由 ModelApiClient 已有 sanitizer 处理;摘要结果写入 SummaryMessage 后
 * 不再过审计落库——审计走 TrajectoryEntity(完整 turns 仍可回放)。
 */
public interface SummaryProvider {

    /**
     * 摘要旧 turns。
     *
     * @param turns 待摘要的消息列表(已按时间顺序,oldest 在前)
     * @param request 当前 AgentRequest(用于 actor / zone hint)
     * @return 摘要文本(不含 "[系统摘要]" 前缀,caller 自行包装)
     * @throws Exception Provider 调用失败 / 超时;caller catch 后走 heuristic 降级
     */
    String summarize(List<AgentMessage> turns, AgentRequest request) throws Exception;
}
