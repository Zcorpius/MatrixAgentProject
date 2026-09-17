package com.matrix.agent.task.identity;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 基于关键词的显式记忆意图检测器(默认实现)。
 *
 * <p>规则(用户指定字面落实):命令含以下任一正向关键词 → {@code true}:
 * <ul>
 *   <li>{@code 记住}、{@code 保存}——最直接的显式记忆动词;</li>
 *   <li>{@code 以后默认}——典型"保存为偏好"语义("以后默认 24 度");</li>
 *   <li>{@code 不要忘记}、{@code 别忘了}——反向强调,要求长期记忆;</li>
 *   <li>{@code 默认用}——"默认用运动模式"类偏好保存;</li>
 *   <li>{@code 长期}、{@code 永远用}——时间维度强调,长期记忆意图明确。</li>
 * </ul>
 *
 * <p>否定短语门(用户硬约束——"不要记住 不应作为安全边界的可接受行为"):
 * 命中以下任一否定短语 → 优先短路返回 {@code false}(在正向匹配之前):
 * <ul>
 *   <li>{@code 不要记住}、{@code 别记住}——明确拒绝记住;</li>
 *   <li>{@code 无需保存}、{@code 不用保存}——明确拒绝保存;</li>
 *   <li>{@code 不要长期保存}——明确拒绝长期记忆;</li>
 *   <li>{@code 删除记忆}——明确要求遗忘。</li>
 * </ul>
 * <p>否定短语优先于正向——避免 {@code contains("记住")} 把"不要记住"误判为 true。
 * NEGATIVE 列表与 POSITIVE 列表互不重叠:"不要忘记"/"别忘了"是 positive(语义"不要忘记=要记住"),
 * 不在 NEGATIVE 内;裸"不要"过宽(会误伤"不要调温度"),也不在 NEGATIVE 内。
 *
 * <p>未命中 / null / empty → {@code false}(保守,默认拒绝 save)。
 *
 * <p><b>已知限制</b>(当前仍接受,后续版本升 LLM-based 解决):
 * <ul>
 *   <li>同义词("给我存下来"/"记牢"/"记下")漏检;</li>
 *   <li>否定短语仅覆盖明确字面——同义否定("不要存进记忆"等)可能漏;</li>
 *   <li>英文 / 方言 / 多语言未覆盖。</li>
 * </ul>
 *
 * <p>关键词大小写不敏感;{@code command.contains(keyword)} 任一命中即返回对应决策。
 */
public final class KeywordMemoryIntentDetector implements MemoryIntentDetector {
    public static final KeywordMemoryIntentDetector INSTANCE = new KeywordMemoryIntentDetector();

    private static final Set<String> MEMORY_SAVE_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "记住", "保存", "以后默认", "不要忘记", "别忘了", "默认用", "长期", "永远用"
    )));

    /** 否定短语——优先短路返回 false,避免 contains("记住") 把"不要记住"误判。 */
    private static final Set<String> MEMORY_SAVE_NEGATIVE_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "不要记住", "别记住", "无需保存", "不用保存", "不要长期保存", "删除记忆"
    )));

    @Override
    public boolean isExplicitMemorySave(String command) {
        if (command == null) return false;
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;
        // 否定短语优先短路——"不要记住"/"别记住"/"删除记忆"等显式拒绝
        for (String neg : MEMORY_SAVE_NEGATIVE_KEYWORDS) {
            if (normalized.contains(neg.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        for (String keyword : MEMORY_SAVE_KEYWORDS) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    KeywordMemoryIntentDetector() { }
}
