package com.matrix.agent.attachment.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class DocumentChunker {
    public static final int CHUNK_CHARS = 800, OVERLAP_CHARS = 120, MAX_CHUNKS = 4096;
    private DocumentChunker() { }
    public static List<DocumentChunk> split(String attachmentId, String text) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() > 2 * 1024 * 1024) throw new IllegalArgumentException("document character bound");
        String version = digest(text);
        List<DocumentChunk> chunks = new ArrayList<>();
        int total = text.codePointCount(0, text.length()), startChar = 0, startOffset = 0;
        while (startChar < total) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int endChar = Math.min(total, startChar + CHUNK_CHARS);
            int endOffset = text.offsetByCodePoints(startOffset, endChar - startChar);
            if (endChar < total) {
                int paragraph = text.lastIndexOf('\n', endOffset - 1);
                if (paragraph >= startOffset && text.codePointCount(startOffset, paragraph + 1) >= 640) {
                    endOffset = paragraph + 1;
                    endChar = startChar + text.codePointCount(startOffset, endOffset);
                }
            }
            if (chunks.size() >= MAX_CHUNKS) throw new IllegalArgumentException("document chunk bound");
            chunks.add(new DocumentChunk(attachmentId, chunks.size(), version, startChar, endChar,
                    text.substring(startOffset, endOffset)));
            if (endChar == total) break;
            startChar = endChar - OVERLAP_CHARS;
            startOffset = text.offsetByCodePoints(endOffset, -OVERLAP_CHARS);
        }
        return List.copyOf(chunks);
    }
    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(64);
            for (byte value : bytes) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hash.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
