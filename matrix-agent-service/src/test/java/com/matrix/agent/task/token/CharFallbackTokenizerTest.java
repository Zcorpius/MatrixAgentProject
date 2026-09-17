package com.matrix.agent.task.token;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * CharFallbackTokenizer 契约测试。
 *
 * <p>验证 char-based fallback 行为——默认装配实现,与旧版
 * AgentEngine.estimateConversationChars 字面等价。
 */
public final class CharFallbackTokenizerTest {
    @Test
    public void countEqualsTextLength() {
        Tokenizer tokenizer = CharFallbackTokenizer.INSTANCE;
        assertEquals(0, tokenizer.count(null));
        assertEquals(0, tokenizer.count(""));
        assertEquals(5, tokenizer.count("hello"));
        // 中文字符也算 1 个 char(UTF-16 code unit);"把主驾温度调到24度" = 10 chars
        assertEquals(10, tokenizer.count("把主驾温度调到24度"));
    }

    @Test
    public void encodingIsCharFallback() {
        assertEquals("char-fallback", CharFallbackTokenizer.INSTANCE.encoding());
    }

    @Test
    public void encodeAndBackIsIdentity() {
        Tokenizer tokenizer = CharFallbackTokenizer.INSTANCE;
        assertEquals("", tokenizer.encodeAndBack(null));
        assertEquals("hello world", tokenizer.encodeAndBack("hello world"));
    }

    @Test
    public void instanceIsSingleton() {
        assertSame(CharFallbackTokenizer.INSTANCE, CharFallbackTokenizer.INSTANCE);
    }
}
