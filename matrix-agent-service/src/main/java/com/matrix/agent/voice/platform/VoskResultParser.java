package com.matrix.agent.voice.platform;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Vosk 结果 JSON 解析(纯工具,只依赖 org.json,无 Vosk native 依赖,JVM 可测)。
 *
 * <p>解析 Vosk endpoint 结果 JSON:
 * <pre>{@code
 * {"text":"开空调","result":[{"word":"开","start":0.1,"end":0.3,"conf":0.92},...],"confidence":..}
 * }</pre>
 * 读顶层 {@code text};按 {@code result} 数组各 word 的 {@code conf} 取<strong>非空数组均值</strong>作为
 * 可解释置信度;无 {@code result} 字段或空数组 → {@code confidenceAvailable=false}(置信度未知,
 * 由 {@link com.matrix.agent.voice.FinalTranscript} 归零)。
 *
 * <p>从 {@code VoskAsrEngine.parseText} 迁入并扩展为带置信度;{@code VoskAsrEngine.feed} 调本类,
 * 引擎自身不再解析 JSON。
 */
public final class VoskResultParser {

    private VoskResultParser() { }

    public static ParsedResult parse(String json) {
        if (json == null) {
            return ParsedResult.noResult("NULL_JSON");
        }
        String text = "";
        float confidence = 0f;
        boolean confidenceAvailable = false;
        int resultWordCount = 0;
        int confidenceWordCount = 0;
        try {
            JSONObject obj = new JSONObject(json);
            text = obj.optString("text", "").trim();
            JSONArray result = obj.optJSONArray("result");
            if (result != null && result.length() > 0) {
                resultWordCount = result.length();
                double sum = 0.0;
                int n = 0;
                for (int i = 0; i < result.length(); i++) {
                    JSONObject word = result.optJSONObject(i);
                    if (word == null) continue;
                    double conf = word.optDouble("conf", Double.NaN);
                    if (!Double.isNaN(conf)) {
                        sum += conf;
                        n++;
                    }
                }
                confidenceWordCount = n;
                if (n > 0) {
                    confidence = (float) (sum / n);
                    confidenceAvailable = true;
                }
            }
        } catch (Exception e) {
            return ParsedResult.noResult("MALFORMED_JSON");
        }
        float clamped = Math.max(0f, Math.min(1f, confidence));
        return new ParsedResult(text, clamped, confidenceAvailable, resultWordCount, confidenceWordCount,
                confidenceAvailable ? "WORD_CONFIDENCE" : "NO_WORD_CONFIDENCE");
    }

    /** 解析结果:text 总是非 null;confidence∈[0,1];confidenceAvailable=false 时 confidence 应视为未知。 */
    public static final class ParsedResult {
        private final String text;
        private final float confidence;
        private final boolean confidenceAvailable;
        private final int resultWordCount;
        private final int confidenceWordCount;
        private final String confidenceSource;

        ParsedResult(String text, float confidence, boolean confidenceAvailable, int resultWordCount,
                int confidenceWordCount, String confidenceSource) {
            this.text = text;
            this.confidence = confidence;
            this.confidenceAvailable = confidenceAvailable;
            this.resultWordCount = resultWordCount;
            this.confidenceWordCount = confidenceWordCount;
            this.confidenceSource = confidenceSource;
        }

        static ParsedResult noResult(String confidenceSource) {
            return new ParsedResult("", 0f, false, 0, 0, confidenceSource);
        }

        public String text() { return text; }
        public float confidence() { return confidence; }
        public boolean confidenceAvailable() { return confidenceAvailable; }
        /** 不含转写原文的 JSON 结构摘要，仅用于现场诊断。 */
        public int resultWordCount() { return resultWordCount; }
        /** 实际带 {@code conf} 字段的词数。 */
        public int confidenceWordCount() { return confidenceWordCount; }
        public String confidenceSource() { return confidenceSource; }
    }
}
