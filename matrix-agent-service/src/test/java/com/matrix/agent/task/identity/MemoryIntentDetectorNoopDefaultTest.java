package com.matrix.agent.task.identity;
import com.matrix.agent.host.di.*;

import com.matrix.agent.intent.MemoryIntentDetector;

import com.matrix.agent.intent.KeywordMemoryIntentDetector;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * Repository 默认不注入 detector(等价 MemoryIntentDetector.NOOP)时,
 * 所有请求 memorySaveAllowed=false(保守起点)。AppContainer 装配 KeywordMemoryIntentDetector
 * 之前,所有 memory.semantic.save 都被 POLICY_REJECTED——宁可让用户发现"我说了记住为什么不存",
 * 也不能让模型自由写。
 */
public final class MemoryIntentDetectorNoopDefaultTest {

    @Test
    public void noopReturnsFalseForNull() {
        assertFalse(MemoryIntentDetector.NOOP.isExplicitMemorySave(null));
    }

    @Test
    public void noopReturnsFalseForExplicitMemoryCommand() {
        // 即便命令含"记住",NOOP 也返回 false——保守默认
        assertFalse(MemoryIntentDetector.NOOP.isExplicitMemorySave("记住我对花生过敏"));
    }

    @Test
    public void noopReturnsFalseForArbitraryString() {
        assertFalse(MemoryIntentDetector.NOOP.isExplicitMemorySave("任意文本"));
    }

    @Test
    public void noopReturnsFalseForEmpty() {
        assertFalse(MemoryIntentDetector.NOOP.isExplicitMemorySave(""));
    }
}
