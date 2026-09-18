package com.matrix.agent.intent;

/**
 * 显式语义记忆意图检测器。
 *
 * <p>问题背景:此前把 {@code memory.semantic.save} 设计为"仅显式",但只是 prompt 约定
 * (BASE_TEMPLATE 文案 + capability description)——R1_LOW_RISK_WRITE 风险级别允许模型
 * 在任意请求里自由调用。"今天天气怎么样"这种纯查询请求里,模型也能调 save 把无关内容
 * 写进 semantic 表,污染长期记忆。
 *
 * <p>用户硬约束:"长期记忆仅由用户决定"。本接口在 Repository.execute 入口(模型调用前)
 * 给出严格判定:只有用户原文明示"记住/保存/以后默认/不要忘记"等动词时,AgentRequest
 * 才被标记为 {@code memorySaveAllowed=true}。memory.semantic.save handler 在 false 时
 * 直接 POLICY_REJECTED,模型重试无效——硬 gate,prompt 约定的双重防线。
 *
 * <p>提供默认实现 {@link KeywordMemoryIntentDetector}——中文关键词匹配。
 * 后续版本可替换为 LLM-based detector(同义词 / 否定 / 多语言),接口签名不变。
 *
 * <p>与 {@link IntentClassifier} 的差异:IntentClassifier 输出 readOnly(查询 vs 写)用于
 * TaskScheduler 抢占判定;MemoryIntentDetector 输出 explicitMemorySave(显式长期记忆意图)
 * 用于 capability handler gate。两者关注点不同,不混接口。
 */
public interface MemoryIntentDetector {
    /**
     * 检测用户输入是否显式要求长期记忆保存。
     *
     * <p>实现规则(保守):
     * <ul>
     *   <li>命中关键词(记住/保存/以后默认/不要忘记/别忘了/默认用/长期/永远用)→ true</li>
     *   <li>未命中 / null / empty → false(保守,默认拒绝 save)</li>
     * </ul>
     *
     * @param command 用户原始 command 文本
     * @return true 表示用户显式要求长期记忆,可放行 memory.semantic.save
     */
    boolean isExplicitMemorySave(String command);

    /** Singleton:始终 false,与 KeywordIntentClassifier fallback 同模式。 */
    MemoryIntentDetector NOOP = command -> false;
}
