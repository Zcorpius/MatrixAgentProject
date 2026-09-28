package com.matrix.agent.data.memory;

import android.util.Log;

import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.data.memory.SemanticMemorySource;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Scoped semantic retrieval: lexical baseline with optional, versioned on-device vector ranking.
 * Reciprocal rank fusion keeps the two score scales separate; values still pass the existing
 * prompt projection boundary and must be read through an authorized memory tool.
 */
public final class SemanticMemorySourceImpl implements SemanticMemorySource {
    private static final String TAG = "MatrixAgent";
    private static final String LAYER = MemoryLayer.SEMANTIC.wireValue();
    /** 单次召回上限——与 EpisodicMemorySourceImpl.DEFAULT_LIMIT 对齐。 */
    private static final int DEFAULT_LIMIT = 5;
    /** 关键词命中得分——每个 token 在 key/value 中出现 +N 分。 */
    private static final double KEYWORD_HIT_BONUS = 1.0;
    /** 关键词命中 key 比 value 加权更高(更精确)。 */
    private static final double KEYWORD_HIT_KEY_MULTIPLIER = 2.0;
    private static final Set<String> STOP_TERMS = Set.of("我的", "什么", "怎么", "多少",
            "请问", "告诉", "记住", "保存", "之前", "现在", "可以", "这个", "那个",
            "我们", "你们", "是否", "已经", "what", "the", "about", "please",
            "remember", "tell", "me", "my");

    private final MemoryRecordDao dao;
    private final int limit;
    public enum Mode { LEXICAL, VECTOR, HYBRID }
    private final com.matrix.agent.embedding.SemanticVectorRecall vectors;
    private final Mode mode;

    public SemanticMemorySourceImpl(MemoryRecordDao dao) {
        this(dao, DEFAULT_LIMIT);
    }

    /** 测试用——注入自定义 limit。 */
    public SemanticMemorySourceImpl(MemoryRecordDao dao, int limit) {
        this(dao, limit, com.matrix.agent.embedding.SemanticVectorRecall.NONE, Mode.LEXICAL);
    }

    public SemanticMemorySourceImpl(MemoryRecordDao dao, int limit,
            com.matrix.agent.embedding.SemanticVectorRecall vectors, Mode mode) {
        if (dao == null) throw new IllegalArgumentException("dao 不能为空");
        this.dao = dao;
        this.vectors = java.util.Objects.requireNonNull(vectors);
        this.mode = java.util.Objects.requireNonNull(mode);
        this.limit = limit <= 0 ? DEFAULT_LIMIT : limit;
    }

    @Override
    public List<MemorySnippet> recallSemantic(MemoryScope scope, String userText, int maxItems) {
        if (scope == null || maxItems <= 0) return Collections.emptyList();
        int effectiveLimit = Math.min(maxItems, limit);

        List<MemoryRecordEntity> rows;
        try {
            rows = dao.queryByUserZoneLayer(scope.getUserId(),
                    scope.getZone().wireValue(), LAYER);
        } catch (Exception ex) {
            Log.w(TAG, "[SemanticMemorySource] recall FAILED user=" + scope.getUserId()
                    + " zone=" + scope.getZone() + " cause=" + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage());
            return Collections.emptyList();
        }

        if (rows == null || rows.isEmpty()) return Collections.emptyList();

        Set<String> queryTokens = tokenize(userText);
        if (queryTokens.isEmpty() && (userText == null || userText.isBlank())) {
            return Collections.emptyList();
        }
        List<Scored> scored = new ArrayList<>(rows.size());
        for (MemoryRecordEntity row : rows) {
            double evidence = matchEvidence(row, queryTokens, userText);
            if (evidence > 0) scored.add(new Scored(row, evidence + row.score));
        }
        // 排序:score 高 → 低;同 score 时按 capturedAtMs 近 → 远
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                if (Double.compare(b.score, a.score) != 0) return Double.compare(b.score, a.score);
                int time = Long.compare(b.row.capturedAtMs, a.row.capturedAtMs);
                return time != 0 ? time : a.row.key.compareTo(b.row.key);
            }
        });

        if (mode != Mode.LEXICAL) {
            List<com.matrix.agent.embedding.SemanticVectorRecall.Hit> vectorHits;
            try { vectorHits = vectors.recall(scope, userText); }
            catch (RuntimeException unavailable) { vectorHits = List.of(); }
            // Hybrid is fail-open to the lexical baseline; VECTOR is a pure comparison mode.
            if (mode == Mode.VECTOR && vectorHits.isEmpty()) return List.of();
            if (!vectorHits.isEmpty()) {
                java.util.Map<String, Double> ranks = new java.util.HashMap<>();
                if (mode == Mode.HYBRID) for (int i = 0; i < scored.size(); i++) {
                    ranks.put(scored.get(i).row.key, 1.0 / (60 + i + 1));
                }
                for (int i = 0; i < vectorHits.size(); i++) {
                    ranks.merge(vectorHits.get(i).key(), 1.0 / (60 + i + 1), Double::sum);
                }
                scored.clear();
                for (var row : rows) if (ranks.containsKey(row.key)) scored.add(new Scored(row, ranks.get(row.key)));
                scored.sort(Comparator.comparingDouble((Scored hit) -> hit.score).reversed()
                        .thenComparing(hit -> hit.row.key));
            }
        }

        List<MemorySnippet> out = new ArrayList<>(Math.min(scored.size(), effectiveLimit));
        for (int i = 0; i < scored.size() && out.size() < effectiveLimit; i++) {
            Scored hit = scored.get(i);
            MemoryRecordEntity row = hit.row;
            out.add(new MemorySnippet(MemoryLayer.SEMANTIC, scope, row.key,
                    row.value == null ? "" : row.value, hit.score,
                    row.capturedAtMs, row.sourceSessionId));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 分词：英文按词，连续中文按二元组，小写化并移除高频停用词。
     *
     * <p>中文连续字符不累加——每个 CJK 字符单独成 token(中文分词需要词典,这里走最保守的
     * 单字切分);CJK 与 Latin 拼接也按字符类型切换边界。
     *
     * <p>接入 embedding 后此方法仍可用——作为 fallback / 解释性 token。
     */
    static Set<String> tokenize(String text) {
        if (text == null || text.isEmpty()) return Collections.emptySet();
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isChinese(c)) {
                // flush Latin accumulator
                if (current.length() > 0) {
                    tokens.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
                if (i + 1 < text.length() && isChinese(text.charAt(i + 1))) {
                    tokens.add(text.substring(i, i + 2));
                }
            } else if (Character.isLetterOrDigit(c)) {
                current.append(c);
            } else {
                // 标点 / 空格 / 其他:边界
                if (current.length() > 0) {
                    tokens.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString().toLowerCase(Locale.ROOT));
        }
        // Single-character CJK matches are too broad for personal-memory retrieval.
        Set<String> filtered = new LinkedHashSet<>();
        for (String t : tokens) {
            if (t.length() >= 2 && !STOP_TERMS.contains(t)) {
                filtered.add(t);
            }
        }
        return filtered;
    }

    private static boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static double matchEvidence(MemoryRecordEntity row, Set<String> queryTokens,
            String userText) {
        double score = MemoryKeyCatalog.relevance(row.key, userText);
        String keyLower = row.key == null ? "" : row.key.toLowerCase(Locale.ROOT);
        String valueLower = row.value == null ? "" : row.value.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (containsTerm(keyLower, token)) {
                score += KEYWORD_HIT_BONUS * KEYWORD_HIT_KEY_MULTIPLIER;
            }
            if (containsTerm(valueLower, token)) {
                score += KEYWORD_HIT_BONUS;
            }
        }
        return score;
    }

    private static boolean containsTerm(String text, String token) {
        if (token.isEmpty()) return false;
        if (isChinese(token.charAt(0))) return text.contains(token);
        for (int index = text.indexOf(token); index >= 0;
                index = text.indexOf(token, index + 1)) {
            int end = index + token.length();
            boolean left = index == 0 || !isAsciiWord(text.charAt(index - 1));
            boolean right = end == text.length() || !isAsciiWord(text.charAt(end));
            if (left && right) return true;
        }
        return false;
    }

    private static boolean isAsciiWord(char value) {
        return (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9');
    }

    private static final class Scored {
        final MemoryRecordEntity row;
        final double score;

        Scored(MemoryRecordEntity row, double score) {
            this.row = row;
            this.score = score;
        }
    }
}
