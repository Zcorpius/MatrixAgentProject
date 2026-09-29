package com.matrix.agent.attachment.retrieval;

/** Character offsets are Unicode code points, start inclusive/end exclusive, in the original text. */
public record DocumentChunk(String attachmentId, int ordinal, String contentVersion,
        int startChar, int endChar, String text) {
    public DocumentChunk {
        if (attachmentId == null || attachmentId.isBlank() || ordinal < 0 || ordinal >= 4096
                || contentVersion == null || !contentVersion.matches("[a-f0-9]{64}")
                || startChar < 0 || endChar <= startChar || text == null
                || text.codePointCount(0, text.length()) != endChar - startChar || endChar-startChar > 800) {
            throw new IllegalArgumentException("invalid document chunk");
        }
    }
}
