package com.matrix.agent.voice.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link VoskResultParser} Vosk 结果 JSON 解析。
 *
 * <p>纯 org.json,无 native 依赖,JVM 可测。守护:word conf 均值聚合、空/无 result → 置信度不可用。
 */
public final class VoskResultParserTest {

    @Test
    public void resultArray_aggregatesWordConfMean() {
        String json = "{\"text\":\"开空调\",\"result\":["
                + "{\"word\":\"开\",\"start\":0.1,\"end\":0.3,\"conf\":0.9},"
                + "{\"word\":\"空调\",\"start\":0.3,\"end\":0.7,\"conf\":0.7}"
                + "]}";
        VoskResultParser.ParsedResult r = VoskResultParser.parse(json);
        assertEquals("开空调", r.text());
        assertTrue(r.confidenceAvailable());
        assertEquals(0.8f, r.confidence(), 0.001f); // (0.9+0.7)/2
        assertEquals(2, r.resultWordCount());
        assertEquals(2, r.confidenceWordCount());
        assertEquals("WORD_CONFIDENCE", r.confidenceSource());
    }

    @Test
    public void emptyResultArray_confidenceUnavailable() {
        VoskResultParser.ParsedResult r = VoskResultParser.parse("{\"text\":\"开\",\"result\":[]}");
        assertEquals("开", r.text());
        assertFalse(r.confidenceAvailable());
        assertEquals(0f, r.confidence(), 0f);
        assertEquals(0, r.resultWordCount());
        assertEquals("NO_WORD_CONFIDENCE", r.confidenceSource());
    }

    @Test
    public void noResultField_confidenceUnavailable() {
        VoskResultParser.ParsedResult r = VoskResultParser.parse("{\"text\":\"你好\"}");
        assertEquals("你好", r.text());
        assertFalse(r.confidenceAvailable());
        assertEquals("NO_WORD_CONFIDENCE", r.confidenceSource());
    }

    @Test
    public void wordsMissingConf_skipped() {
        // 部分 word 无 conf 字段 → 跳过,仅按有 conf 的 word 算均值
        String json = "{\"text\":\"x\",\"result\":["
                + "{\"word\":\"a\",\"conf\":0.8},"
                + "{\"word\":\"b\"}"
                + "]}";
        VoskResultParser.ParsedResult r = VoskResultParser.parse(json);
        assertTrue(r.confidenceAvailable());
        assertEquals(0.8f, r.confidence(), 0.001f);
        assertEquals(2, r.resultWordCount());
        assertEquals(1, r.confidenceWordCount());
    }

    @Test
    public void nullJson_returnsEmpty() {
        VoskResultParser.ParsedResult r = VoskResultParser.parse(null);
        assertEquals("", r.text());
        assertFalse(r.confidenceAvailable());
        assertEquals("NULL_JSON", r.confidenceSource());
    }

    @Test
    public void malformedJson_returnsEmpty() {
        VoskResultParser.ParsedResult r = VoskResultParser.parse("not json");
        assertEquals("", r.text());
        assertFalse(r.confidenceAvailable());
        assertEquals("MALFORMED_JSON", r.confidenceSource());
    }
}
