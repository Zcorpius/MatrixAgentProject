package com.matrix.agent.attachment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 附件文本提取器（输入交互增强 I6 §9.2，2A）。
 *
 * <p>三道防线全部在 Host 进程内完成：大小上限（读流式计数，不信任 Content-Length）、
 * MIME 白名单（text/* + JSON/XML；嗅探兜底拒绝二进制）、字符上限（16k Unicode 字符，
 * 按剩余字节截断到合法 UTF-8 边界）。图片在 OCR 端口选型前 fail-closed——
 * UNSUPPORTED_MEDIA，绝不以文件名或模糊描述伪装为“图片已理解”。</p>
 */
public final class AttachmentTextExtractor {

    /** 摄取字节上限（2 MiB）：超过即拒绝，不部分提取。 */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** 提取字符上限（16k Unicode 字符）：模型投影预算的安全上界。 */
    public static final int MAX_CHARS = 16_000;
    /** 二进制嗅探窗口：前 4KB 中不可打印字符占比超过阈值视为二进制。 */
    private static final int SNIFF_WINDOW = 4096;
    private static final double BINARY_PRINTABLE_RATIO = 0.90d;

    private static final Set<String> TEXT_MIME_PREFIXES = Set.of("text/");
    private static final Set<String> TEXT_MIME_EXACT = Set.of(
            "application/json", "application/xml", "application/x-yaml", "application/rss+xml");

    public record Extraction(String text, String sniffedMime, long sourceByteSize) { }

    public static final class RejectedException extends IOException {
        public final int errorCode;

        RejectedException(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    private AttachmentTextExtractor() { }

    /**
     * 从输入流提取受限文本。declaredMime 只作提示——内容嗅探（可打印率 + BOM/尖括号
     * 特征）才是权威；image/* 直接拒绝（OCR 未选型，fail-closed）。
     */
    public static Extraction extract(InputStream input, String declaredMime) throws IOException {
        if (declaredMime != null && declaredMime.toLowerCase(Locale.ROOT)
                .startsWith("image/")) {
            throw new RejectedException(
                    com.matrix.agent.api.conversation.ConversationAttachment
                            .ERROR_UNSUPPORTED_MEDIA,
                    "图片附件需要 OCR 端口选型完成后才可摄取");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long total = 0;
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) > 0) {
            total += read;
            if (total > MAX_BYTES) {
                throw new RejectedException(
                        com.matrix.agent.api.conversation.ConversationAttachment.ERROR_TOO_LARGE,
                        "附件超过 " + (MAX_BYTES / 1024 / 1024) + " MiB 上限");
            }
            buffer.write(chunk, 0, read);
        }
        byte[] bytes = buffer.toByteArray();
        if (bytes.length == 0) {
            throw new RejectedException(
                    com.matrix.agent.api.conversation.ConversationAttachment.ERROR_EMPTY_TEXT,
                    "附件为空");
        }
        String sniffed = sniffMime(bytes, declaredMime);
        if (!isTextMime(sniffed)) {
            throw new RejectedException(
                    com.matrix.agent.api.conversation.ConversationAttachment
                            .ERROR_UNSUPPORTED_MEDIA,
                    "仅支持文本类附件，嗅探为 " + sniffed);
        }
        String decoded = new String(bytes, utf8WithBomSkip(bytes));
        if (decoded.isBlank()) {
            throw new RejectedException(
                    com.matrix.agent.api.conversation.ConversationAttachment.ERROR_EMPTY_TEXT,
                    "附件无有效文本");
        }
        return new Extraction(capChars(decoded), sniffed, bytes.length);
    }

    /** 截断到 code point 上限，绝不把代理对从中间切开。 */
    static String capChars(String value) {
        if (value.codePointCount(0, value.length()) <= MAX_CHARS) return value;
        int end = value.offsetByCodePoints(0, MAX_CHARS);
        return value.substring(0, end) + "\n…(已截断)";
    }

    private static boolean isTextMime(String mime) {
        if (TEXT_MIME_PREFIXES.stream().anyMatch(mime::startsWith)) return true;
        return TEXT_MIME_EXACT.contains(mime);
    }

    /** 内容嗅探：可打印率 + JSON/XML/HTML 尖括号特征；无魔法数时回退声明 MIME。 */
    static String sniffMime(byte[] bytes, String declaredMime) {
        int window = Math.min(bytes.length, SNIFF_WINDOW);
        int printable = 0;
        for (int i = 0; i < window; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0x09 || b == 0x0A || b == 0x0D || b >= 0x20) printable++;
        }
        boolean mostlyPrintable = (double) printable / window >= BINARY_PRINTABLE_RATIO;
        if (!mostlyPrintable) return "application/octet-stream";
        if (window > 0 && (bytes[0] == '{' || bytes[0] == '[')) {
            return "application/json";
        }
        if (startsWithIgnoringWhitespace(bytes, '<')) return "text/xml";
        String declared = declaredMime == null ? "" : declaredMime.trim()
                .toLowerCase(Locale.ROOT);
        int semicolon = declared.indexOf(';');
        if (semicolon > 0) declared = declared.substring(0, semicolon);
        return declared.isEmpty() ? "text/plain" : declared;
    }

    private static boolean startsWithIgnoringWhitespace(byte[] bytes, char expected) {
        for (byte b : bytes) {
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == 0xEF
                    || b == 0xBB || b == 0xBF) continue;
            return b == expected;
        }
        return false;
    }

    private static Charset utf8WithBomSkip(byte[] bytes) {
        return StandardCharsets.UTF_8; // BOM 字符解碼为 U+FEFF，由净化阶段剥离
    }
}
