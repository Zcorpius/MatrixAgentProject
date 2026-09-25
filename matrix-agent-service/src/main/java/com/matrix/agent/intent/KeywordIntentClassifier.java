package com.matrix.agent.intent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 基于关键词的查询/写意图分类器(默认实现)。
 *
 * <p>规则(保守,未知按写处理):
 * <ol>
 *   <li>命令同时含读关键词和写关键词 → {@code false}(模糊,按写)</li>
 *   <li>命令只含读关键词 → {@code true}</li>
 *   <li>命令只含写关键词 → {@code false}</li>
 *   <li>命令两者都不含 → {@code false}(未知,按写)</li>
 * </ol>
 *
 * <p>关键词集合保守——只把语义清晰的中文动词纳入,英文只接受 capability 名字常见的
 * get/set/open/close。粗粒度匹配,容许 LLM 决策后 task-level hint 进一步细化。
 */
public final class KeywordIntentClassifier implements IntentClassifier {
    public static final KeywordIntentClassifier INSTANCE = new KeywordIntentClassifier();

    private static final Set<String> READ_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "查", "查询", "查一下", "电量", "胎压", "多少", "什么", "状态", "状态如何",
            "get", "read", "query", "ask", "list", "show", "告诉我", "我的"
    )));

    private static final Set<String> WRITE_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "设", "设置", "调", "调整", "调到", "打开", "开启", "关闭", "关掉", "启动",
            "导航", "导航到", "记住", "保存", "存一下", "切换", "更改", "改",
            "播放", "暂停", "继续看", "切歌", "下一首", "上一首", "快进", "快退",
            "set", "open", "close", "toggle", "start", "save", "remember", "navigate"
    )));

    @Override
    public boolean isReadOnly(String command) {
        if (command == null) return false;
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;
        boolean hasRead = false;
        boolean hasWrite = false;
        for (String keyword : READ_KEYWORDS) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                hasRead = true;
                break;
            }
        }
        for (String keyword : WRITE_KEYWORDS) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                hasWrite = true;
                break;
            }
        }
        // 同时含读写 / 都不含 / 只含写 → false
        // 只含读 → true
        return hasRead && !hasWrite;
    }

    /**
     * 结构化分类——规则命中 confidence=1.0;模糊(同时含读/写 / 都不含)confidence=0.5。
     *
     * <p>规则与 {@link #isReadOnly} 一致;reason 描述命中状态(用于审计 / 日志,不含 PII)。
     */
    @Override
    public IntentResult classify(String command) {
        if (command == null || command.trim().isEmpty()) {
            return IntentResult.unknown("empty-command", 0.0);
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        boolean hasRead = false;
        boolean hasWrite = false;
        for (String keyword : READ_KEYWORDS) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                hasRead = true;
                break;
            }
        }
        for (String keyword : WRITE_KEYWORDS) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                hasWrite = true;
                break;
            }
        }
        if (hasRead && hasWrite) {
            return IntentResult.unknown("mixed-read-write-keywords", 0.5);
        }
        if (hasRead) {
            return IntentResult.readOnly("read-keyword-match");
        }
        if (hasWrite) {
            return IntentResult.write("write-keyword-match");
        }
        return IntentResult.unknown("no-keyword-match", 0.5);
    }

    KeywordIntentClassifier() { }
}
