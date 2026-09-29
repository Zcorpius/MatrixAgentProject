package com.matrix.agent.model;

import java.util.function.Consumer;

/** Emits only characters inside a top-level JSON summary string, including split escape sequences. */
final class SummaryStreamDecoder {
    private final Consumer<String> sink;
    private final StringBuilder value = new StringBuilder();
    private final StringBuilder unicode = new StringBuilder();
    private int depth;
    private boolean string, escape, key, summary, expectingKey, expectingValue;
    private String lastKey = "";
    private char highSurrogate;
    SummaryStreamDecoder(Consumer<String> sink) { this.sink = sink; }
    void append(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (string) {
                if (unicode.length() > 0) {
                    if (Character.digit(c, 16) < 0) throw new IllegalArgumentException("JSON unicode escape");
                    unicode.append(c);
                    if (unicode.length() == 5) {
                        appendDecoded((char) Integer.parseInt(unicode.substring(1), 16), out);
                        unicode.setLength(0);
                    }
                } else if (escape) {
                    escape = false;
                    if (c == 'u') unicode.append('u');
                    else appendDecoded(switch (c) {
                        case '"', '\\', '/' -> c;
                        case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                        default -> throw new IllegalArgumentException("JSON escape");
                    }, out);
                } else if (c == '\\') escape = true;
                else if (c == '"') {
                    if (summary && highSurrogate != 0) throw new IllegalArgumentException("JSON surrogate");
                    string = false;
                    if (key) { lastKey = value.toString(); expectingKey = false; }
                    summary = false;
                    highSurrogate = 0;
                } else appendDecoded(c, out);
            } else if (c == '{' || c == '[') {
                depth++; if (depth == 1) expectingKey = c == '{';
            } else if (c == '}' || c == ']') depth--;
            else if (c == ':' && depth == 1) expectingValue = true;
            else if (c == ',' && depth == 1) { expectingKey = true; expectingValue = false; }
            else if (c == '"') {
                string = true; key = depth == 1 && expectingKey;
                summary = depth == 1 && expectingValue && "summary".equals(lastKey);
                expectingValue = false; value.setLength(0);
            }
        }
        if (out.length() > 0) sink.accept(out.toString());
    }
    private void appendDecoded(char c, StringBuilder out) {
        if (key && value.length() < 64) value.append(c);
        if (!summary) return;
        if (highSurrogate != 0) {
            if (!Character.isLowSurrogate(c)) throw new IllegalArgumentException("JSON surrogate");
            out.append(highSurrogate).append(c); highSurrogate = 0;
        } else if (Character.isHighSurrogate(c)) highSurrogate = c;
        else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("JSON surrogate");
        else out.append(c);
    }
}
