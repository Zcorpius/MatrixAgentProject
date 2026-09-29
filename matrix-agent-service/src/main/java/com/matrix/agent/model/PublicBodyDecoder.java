package com.matrix.agent.model;

import java.util.List;
import java.util.function.Consumer;

/** Incremental tag boundary parser. It retains prefixes across arbitrary token/UTF-16 boundaries. */
final class PublicBodyDecoder {
    private static final List<String> OPEN = List.of("<think>", "<analysis>", "<tool_call>");
    private final StringBuilder pending = new StringBuilder();
    private final StringBuilder body = new StringBuilder();
    private final Consumer<String> sink;
    private final boolean suppressJson;
    private String closeTag;
    private boolean initial = true;
    private boolean hiddenJson;
    private boolean finished;
    PublicBodyDecoder(Consumer<String> sink, boolean suppressJson) { this.sink = sink; this.suppressJson = suppressJson; }

    void append(String fragment) {
        if (finished) throw new IllegalStateException("body already finished");
        if (fragment == null || fragment.isEmpty() || hiddenJson) return;
        StringBuilder visible = new StringBuilder();
        for (int offset = 0; offset < fragment.length() && !hiddenJson; offset++) {
            pending.append(fragment.charAt(offset));
            while (pending.length() > 0) {
                if (initial && Character.isWhitespace(pending.charAt(0))) {
                    pending.deleteCharAt(0); continue;
                }
                if (closeTag == null && initial && suppressJson && (pending.charAt(0) == '{' || pending.charAt(0) == '['
                        || pending.charAt(0) == '`')) {
                    hiddenJson = true; pending.setLength(0); break;
                }
                String remaining = pending.toString();
                if (closeTag != null) {
                    int end = remaining.indexOf(closeTag);
                    if (end >= 0) { pending.delete(0, end + closeTag.length()); closeTag = null; continue; }
                    int retained = prefixSuffix(remaining, List.of(closeTag));
                    pending.delete(0, pending.length() - retained);
                    break;
                }
                String opening = OPEN.stream().filter(remaining::startsWith).findFirst().orElse(null);
                if (opening != null) {
                    closeTag = "</" + opening.substring(1);
                    pending.delete(0, opening.length()); continue;
                }
                if (OPEN.stream().anyMatch(tag -> tag.startsWith(remaining))) break;
                char first = pending.charAt(0);
                if (Character.isHighSurrogate(first)) {
                    if (pending.length() == 1) break;
                    if (!Character.isLowSurrogate(pending.charAt(1))) throw new IllegalArgumentException("body surrogate");
                    visible.append(first).append(pending.charAt(1));
                    pending.delete(0, 2);
                } else {
                    if (Character.isLowSurrogate(first)) throw new IllegalArgumentException("body surrogate");
                    visible.append(first); pending.deleteCharAt(0);
                }
                initial = false;
            }
        }
        if (body.length() + visible.length() > 65_536) throw new IllegalStateException("body size exceeded");
        if (visible.length() > 0) { body.append(visible); sink.accept(visible.toString()); }
    }

    boolean finish() {
        finished = true;
        // Unclosed markup / incomplete surrogate never becomes user-visible at EOF.
        return closeTag == null && pending.length() == 0;
    }
    String text() { return body.toString(); }
    private static int prefixSuffix(String text, List<String> tags) {
        int keep = 0;
        for (String tag : tags) for (int n = 1; n < tag.length() && n <= text.length(); n++) {
            if (text.endsWith(tag.substring(0, n))) keep = Math.max(keep, n);
        }
        return keep;
    }
}
