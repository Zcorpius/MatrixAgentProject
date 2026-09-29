package com.matrix.agent.model;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PublicBodyDecoderTest {
    @Test public void surrogatePairsSurviveEverySplitWithoutPublishingHalfScalars() {
        String text = "正文😀结束";
        for (int split = 0; split <= text.length(); split++) {
            StringBuilder visible = new StringBuilder();
            var decoder = new PublicBodyDecoder(part -> {
                assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
                assertFalse(Character.isLowSurrogate(part.charAt(0)));
                visible.append(part);
            }, false);
            decoder.append(text.substring(0, split)); decoder.append(text.substring(split));
            assertTrue(decoder.finish()); assertEquals(text, visible.toString());
        }
    }
    @Test public void malformedJsonEscapedScalarsNeverReachPublicBody() {
        for (String invalid : new String[]{"\uD800x", "\uDC00"}) {
            StringBuilder visible = new StringBuilder();
            var decoder = new PublicBodyDecoder(visible::append, false);
            assertThrows(IllegalArgumentException.class, () -> decoder.append(invalid));
            assertEquals("", visible.toString());
        }
        var unfinished = new PublicBodyDecoder(ignored -> fail("half scalar emitted"), false);
        unfinished.append("\uD800"); assertFalse(unfinished.finish());
    }
    @Test public void compatibilitySummaryRejectsDanglingEscapedSurrogateAtClosingQuote() {
        var decoder = new SummaryStreamDecoder(ignored -> fail("half scalar emitted"));
        assertThrows(IllegalArgumentException.class, () -> decoder.append("{\"summary\":\"\\ud800\"}"));
    }
}
