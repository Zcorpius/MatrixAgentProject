package com.matrix.agent.task.identity;

/**
 * 模型调用前的查询/写意图分类器。
 *
 * <p>问题背景:早期把 Repository 所有请求都标为 {@code readOnlyHint=true}
 * 让主驾优先调度可触发,但这违反了"车控写操作不能被半路强制中断"的安全规则——
 * 主驾"打开空调""设座椅加热"等写操作也会抢占副驾的写操作。
 *
 * <p>本接口在 LLM 决策之前(Repository.execute build request 阶段)给出保守的意图分类。
 * 实现规则:
 * <ul>
 *   <li>明确含查询关键词(查/查电量/查胎压/get/查询)→ {@code true}(只读,允许被抢占)</li>
 *   <li>明确含写关键词(设/调/开/关/启动/导航到/记住/保存)→ {@code false}(写,不可抢占)</li>
 *   <li>未知 / 模糊 / 同时含读写关键词 → {@code false}(保守,按写操作处理)</li>
 * </ul>
 *
 * <p>提供默认实现 {@link KeywordIntentClassifier}——简单关键词匹配。
 * 后续可替换为 LLM-based classifier / Embedding classifier,接口签名不变。
 *
 * <p>新增 {@link #classify(String)} 返回结构化
 * {@link IntentResult}(read-only/write/unknown + 置信度 + 理由)。旧 {@link #isReadOnly(String)}
 * 保留 default 实现(转发 {@code classify(...).isReadOnly()}),调用方零回归——
 * Repository 主路径同时切换为 classify,在 confidence<0.7 时按写操作保守处理。
 */
public interface IntentClassifier {
    /**
     * 旧 API——boolean 形式的意图判定。
     *
     * <p>默认实现转发 {@link #classify(String)};实现方应优先实现 classify。
     */
    default boolean isReadOnly(String command) {
        return classify(command).isReadOnly();
    }

    /**
     * 新 API——结构化意图分类(支持 LLM-based classifier 输出模糊判定)。
     *
     * @param command 用户原始 command 文本
     * @return IntentResult(非 null,unknown 情况下 readOnly=false confidence<0.7)
     */
    IntentResult classify(String command);
}
