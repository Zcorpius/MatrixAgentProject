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

/**
 * Semantic Memory 召回源——基于 MemoryRecordDao 的语义层召回。
 *
 * <p>替代旧版 {@link EmptySemanticMemorySource}——按 (userId, zone, layer="semantic")
 * 拉取所有语义层记录,用关键词匹配 + score 排序。
 *
 * <p><b>不引入 embedding / 向量 DB</b>——硬约束纯 Java,Android 端无开箱即用向量数据库。
 * 关键词匹配:把 userText 切词(空格 / 标点 / 中文按字符),与 MemoryRecordEntity.key / value
 * 匹配命中次数作为 score;MemoryRecordEntity.score 字段作为静态优先级加分。
 *
 * <p><b>候选</b>:接入 on-device embedding(如 TensorFlow Lite MiniLM),score 改为
 * cosine similarity + 关键词命中的加权融合。
 *
 * <p><b>fail-open</b>:Dao 异常仅 log,返回空 list——记忆是增强能力,不能成为车机任务入口的单点故障。
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

    private final MemoryRecordDao dao;
    private final int limit;

    public SemanticMemorySourceImpl(MemoryRecordDao dao) {
        this(dao, DEFAULT_LIMIT);
    }

    /** 测试用——注入自定义 limit。 */
    public SemanticMemorySourceImpl(MemoryRecordDao dao, int limit) {
        if (dao == null) throw new IllegalArgumentException("dao 不能为空");
        this.dao = dao;
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
        List<Scored> scored = new ArrayList<>(rows.size());
        for (MemoryRecordEntity row : rows) {
            double score = computeScore(row, queryTokens);
            scored.add(new Scored(row, score));
        }
        // 排序:score 高 → 低;同 score 时按 capturedAtMs 近 → 远
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                if (Double.compare(b.score, a.score) != 0) return Double.compare(b.score, a.score);
                return Long.compare(b.row.capturedAtMs, a.row.capturedAtMs);
            }
        });

        List<MemorySnippet> out = new ArrayList<>(Math.min(scored.size(), effectiveLimit));
        for (int i = 0; i < scored.size() && out.size() < effectiveLimit; i++) {
            MemoryRecordEntity row = scored.get(i).row;
            out.add(MemorySnippet.of(MemoryLayer.SEMANTIC, scope, row.key,
                    row.value == null ? "" : row.value));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 简单分词:空格 / 标点切英文;中文按单字。小写化去重。
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
                tokens.add(String.valueOf(c));
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
        // 过滤过短 token(1 字符的中文 token 保留——中文单字往往有意义)
        Set<String> filtered = new LinkedHashSet<>();
        for (String t : tokens) {
            if (t.length() >= 2 || (t.length() == 1 && isChinese(t.charAt(0)))) {
                filtered.add(t);
            }
        }
        return filtered;
    }

    private static boolean isChinese(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private static double computeScore(MemoryRecordEntity row, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            // 无 query → 用静态 score(预填的优先级)
            return row.score;
        }
        double score = row.score;
        String keyLower = row.key == null ? "" : row.key.toLowerCase(Locale.ROOT);
        String valueLower = row.value == null ? "" : row.value.toLowerCase(Locale.ROOT);
        for (String token : queryTokens) {
            if (keyLower.contains(token)) {
                score += KEYWORD_HIT_BONUS * KEYWORD_HIT_KEY_MULTIPLIER;
            }
            if (valueLower.contains(token)) {
                score += KEYWORD_HIT_BONUS;
            }
        }
        return score;
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
