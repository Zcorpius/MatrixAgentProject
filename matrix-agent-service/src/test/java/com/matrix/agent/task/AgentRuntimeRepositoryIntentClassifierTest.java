package com.matrix.agent.task;
import com.matrix.agent.host.di.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.matrix.agent.intent.FallbackIntentClassifier;
import com.matrix.agent.intent.IntentClassifier;
import com.matrix.agent.intent.IntentResult;
import com.matrix.agent.intent.KeywordIntentClassifier;
import com.matrix.agent.intent.LlmIntentClassifier;

/**
 * Repository.setIntentClassifier + Fallback 装配契约测试。
 *
 * <p>评审发现 AppContainer 装的是 KeywordIntentClassifier.INSTANCE,LlmIntentClassifier /
 * FallbackIntentClassifier 类已存在但完全没用上。本测试验证 Repository 接受任意 IntentClassifier
 * 实例,并支持运行时切换(setIntentClassifier)——为 AppContainer 装配 FallbackIntentClassifier
 * 提供基础。
 *
 * <p>AppContainer 实际装配路径(Fallback vs Keyword)由 androidTest 覆盖,本测试只验证
 * Repository 层的契约。
 */
public final class AgentRuntimeRepositoryIntentClassifierTest {

    @Test
    public void fallbackClassifierFallsBackToKeywordWhenLlmFails() {
        // 用 throwing LLM 包装 Fallback
        IntentClassifier throwingLlm = new IntentClassifier() {
            @Override
            public IntentResult classify(String command) {
                throw new LlmIntentClassifier.LlmClassificationException(
                        new RuntimeException("simulated provider failure"));
            }
            @Override
            public boolean isReadOnly(String command) {
                throw new LlmIntentClassifier.LlmClassificationException(
                        new RuntimeException("simulated provider failure"));
            }
        };
        FallbackIntentClassifier fallback = new FallbackIntentClassifier(
                throwingLlm, KeywordIntentClassifier.INSTANCE);

        // "查电量" 是查询类,Keyword 应判定 readOnly
        IntentResult result = fallback.classify("查电量");
        assertTrue("LLM 失败时 Fallback 应退 Keyword", result.getReason().contains("primary-failed"));
    }

    @Test
    public void fallbackClassifierReturnsLlmResultWhenHighConfidence() {
        IntentClassifier highConfidenceLlm = new IntentClassifier() {
            @Override
            public IntentResult classify(String command) {
                return IntentResult.readOnly("llm-read");
            }
            @Override
            public boolean isReadOnly(String command) {
                return true;
            }
        };
        FallbackIntentClassifier fallback = new FallbackIntentClassifier(
                highConfidenceLlm, KeywordIntentClassifier.INSTANCE);

        IntentResult result = fallback.classify("查询车辆状态");
        assertEquals("高置信度 LLM 结果应直接返回", "llm-read", result.getReason());
    }

    @Test
    public void fallbackClassifierFallsBackOnLowConfidence() {
        // LLM 返回低置信度 UNKNOWN → 退 Keyword
        IntentClassifier lowConfidenceLlm = new IntentClassifier() {
            @Override
            public IntentResult classify(String command) {
                return IntentResult.unknown("llm-uncertain", 0.3);
            }
            @Override
            public boolean isReadOnly(String command) {
                return false;
            }
        };
        FallbackIntentClassifier fallback = new FallbackIntentClassifier(
                lowConfidenceLlm, KeywordIntentClassifier.INSTANCE);

        IntentResult result = fallback.classify("打开空调");
        assertTrue("低置信度应退 Keyword", result.getReason().contains("primary-low-confidence"));
    }

    @Test
    public void fallbackClassifierHandlesEmptyCommand() {
        IntentClassifier alwaysLlm = new IntentClassifier() {
            @Override
            public IntentResult classify(String command) {
                throw new AssertionError("LLM 不该被调");
            }
            @Override
            public boolean isReadOnly(String command) {
                throw new AssertionError("LLM 不该被调");
            }
        };
        FallbackIntentClassifier fallback = new FallbackIntentClassifier(
                alwaysLlm, KeywordIntentClassifier.INSTANCE);

        IntentResult result = fallback.classify("");
        assertEquals("empty 命令直接 UNKNOWN,不调 LLM", 0.0, result.getConfidence(), 0.001);
    }
}
