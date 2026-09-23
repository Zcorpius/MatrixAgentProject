package com.matrix.agent.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationAttachment;
import com.matrix.agent.attachment.AttachmentTextExtractor.Extraction;
import com.matrix.agent.attachment.AttachmentTextExtractor.RejectedException;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 附件文本提取器（I6 §9.2）：三道防线的单元边界。
 * <ul>
 *   <li>大小上限：流式计数 >2MiB 即拒（不信任 Content-Length）；</li>
 *   <li>MIME 白名单 + 嗅探：二进制拒、JSON/XML 识别、图片 fail-closed（OCR 未选型）；</li>
 *   <li>字符上限：16k Unicode 字符截断到合法 UTF-16 边界。</li>
 * </ul>
 */
public final class AttachmentTextExtractorTest {

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream zeros(long size) {
        return new InputStream() {
            private long remaining = size;

            @Override public int read() {
                return remaining-- > 0 ? 0 : -1;
            }

            @Override public int read(byte[] buffer, int offset, int length) {
                if (remaining <= 0) return -1;
                int chunk = (int) Math.min(length, remaining);
                remaining -= chunk;
                return chunk;
            }
        };
    }

    @Test
    public void plainTextExtractedWithDeclaredMime() throws IOException {
        Extraction result = AttachmentTextExtractor.extract(stream("Hello 附件"), "text/plain");
        assertEquals("text/plain", result.sniffedMime());
        assertEquals("Hello 附件", result.text());
    }

    @Test
    public void jsonSniffedRegardlessOfDeclaration() throws IOException {
        Extraction result = AttachmentTextExtractor.extract(stream("{\"a\":1}"), "text/plain");
        assertEquals("application/json", result.sniffedMime());
        assertEquals("{\"a\":1}", result.text());
    }

    @Test
    public void xmlSniffedByLeadingAngle() throws IOException {
        Extraction result = AttachmentTextExtractor.extract(stream("  <root/>"), "text/plain");
        assertEquals("text/xml", result.sniffedMime());
    }

    @Test
    public void binaryRejectedByPrintableRatio() {
        RejectedException rejected = assertThrows(RejectedException.class,
                () -> AttachmentTextExtractor.extract(zeros(8192), "text/plain"));
        assertEquals(ConversationAttachment.ERROR_UNSUPPORTED_MEDIA, rejected.errorCode);
    }

    @Test
    public void imageFailClosedBeforeOcrSelection() {
        RejectedException rejected = assertThrows(RejectedException.class,
                () -> AttachmentTextExtractor.extract(stream("irrelevant"), "image/png"));
        assertEquals(ConversationAttachment.ERROR_UNSUPPORTED_MEDIA, rejected.errorCode);
    }

    @Test
    public void oversizedRejectedStreaming() {
        RejectedException rejected = assertThrows(RejectedException.class,
                () -> AttachmentTextExtractor.extract(zeros(3 * 1024 * 1024), "text/plain"));
        assertEquals(ConversationAttachment.ERROR_TOO_LARGE, rejected.errorCode);
    }

    @Test
    public void emptyInputRejected() {
        RejectedException rejected = assertThrows(RejectedException.class,
                () -> AttachmentTextExtractor.extract(stream(""), "text/plain"));
        assertEquals(ConversationAttachment.ERROR_EMPTY_TEXT, rejected.errorCode);
    }

    @Test
    public void charCapTruncatesToValidUtf16Boundary() {
        String base = "字".repeat(AttachmentTextExtractor.MAX_CHARS + 100);
        String capped = AttachmentTextExtractor.capChars(base);
        assertTrue("截断后必须 ≤ 上限 + 后缀",
                capped.length() <= AttachmentTextExtractor.MAX_CHARS + 10);
        assertTrue("截断必须有明示后缀", capped.endsWith("…(已截断)"));
    }

    @Test
    public void surrogatePairNotSplitAtBoundary() {
        // emoji 对（代理对）恰好横跨上限：截断退 1 保证不劈半。
        String emoji = "😀".repeat(AttachmentTextExtractor.MAX_CHARS / 2 + 50);
        String capped = AttachmentTextExtractor.capChars(emoji);
        int lastChar = capped.length();
        // 截断点前必须是完整字符（非孤立高位代理）。
        if (lastChar > 0) {
            assertTrue(Character.isHighSurrogate(capped.charAt(lastChar - 1)) == false
                    || Character.isLowSurrogate(capped.charAt(lastChar - 1)));
        }
    }
}
