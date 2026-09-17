package com.matrix.agent.data.memory;

import android.util.Log;

import com.matrix.agent.data.memory.EpisodicMemorySource;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Episodic Memory 召回源——基于 SessionHistoryDao 的近期会话历史。
 *
 * <p>替代 {@link EmptyEpisodicMemorySource}——按 (userId, zone) 召回近期
 * N 条会话,作为 episodic snippet 给 {@link com.matrix.agent.task.prompt.DefaultPromptBuilder}
 * 拼装到 system prompt "[episodic] sessionId" 段。
 *
 * <p><b>LRU 缓存</b>:按 (userId, zone) 缓存召回结果 5 分钟,避免每次 buildSystemPrompt 都
 * 查 SessionHistoryDao(虽快,但首 token 时间敏感)。缓存上限 32 个 user-zone 组合,LRU
 * 驱逐最老。
 *
 * <p><b>fail-open</b>:Dao 异常仅 log,返回空 list——记忆是增强能力,不能成为车机任务入口的单点故障。
 *
 * <p><b>snippet key 约定</b>:"session#" + sessionId.substring(0, 8)(短 hash);value = finalState
 * 含 PII 风险,**不**写入 snippet(与 DefaultPromptBuilder.formatRecalledMemory 一致——
 * snippet 仅拼 layer + key,value 不进 prompt)。
 */
public final class EpisodicMemorySourceImpl implements EpisodicMemorySource {
    private static final String TAG = "MatrixAgent";

    /** 单次召回上限——保守,与 AgentEngine.RECALL_LIMIT_SYSTEM_PROMPT=8 + Preference 占用对齐。 */
    static final int DEFAULT_LIMIT = 5;
    /** 缓存 TTL——5 分钟内同 user-zone 复用结果。 */
    static final long CACHE_TTL_MS = 5L * 60L * 1000L;
    /** LRU 缓存上限——32 个 user-zone 组合(车机场景:主驾 + 副驾 + 后排,远不到 32)。 */
    static final int CACHE_MAX_ENTRIES = 32;

    private final SessionHistoryDao dao;
    private final int limit;
    private final LinkedHashMap<String, CacheEntry> cache;

    public EpisodicMemorySourceImpl(SessionHistoryDao dao) {
        this(dao, DEFAULT_LIMIT);
    }

    /** 测试用——注入自定义 limit。 */
    public EpisodicMemorySourceImpl(SessionHistoryDao dao, int limit) {
        if (dao == null) throw new IllegalArgumentException("dao 不能为空");
        this.dao = dao;
        this.limit = limit <= 0 ? DEFAULT_LIMIT : limit;
        this.cache = new LinkedHashMap<String, CacheEntry>(CACHE_MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > CACHE_MAX_ENTRIES;
            }
        };
    }

    @Override
    public List<MemorySnippet> recallEpisodic(MemoryScope scope, String userText, int maxItems) {
        if (scope == null || maxItems <= 0) return Collections.emptyList();
        String cacheKey = scope.getUserId() + "@" + scope.getZone().wireValue();
        int effectiveLimit = Math.min(maxItems, limit);

        synchronized (cache) {
            CacheEntry hit = cache.get(cacheKey);
            if (hit != null && !hit.isExpired()) {
                return hit.truncate(effectiveLimit);
            }
        }

        List<MemorySnippet> fresh;
        try {
            List<SessionHistoryEntity> rows = dao.queryByUserZone(
                    scope.getUserId(), scope.getZone().wireValue(), limit);
            fresh = toSnippets(scope, rows, effectiveLimit);
        } catch (Exception ex) {
            Log.w(TAG, "[EpisodicMemorySource] recall FAILED user=" + scope.getUserId()
                    + " zone=" + scope.getZone() + " cause=" + ex.getClass().getSimpleName()
                    + ": " + ex.getMessage());
            return Collections.emptyList();
        }

        synchronized (cache) {
            cache.put(cacheKey, new CacheEntry(fresh, System.currentTimeMillis()));
        }
        return fresh.size() > effectiveLimit
                ? Collections.unmodifiableList(new ArrayList<>(fresh.subList(0, effectiveLimit)))
                : fresh;
    }

    private static List<MemorySnippet> toSnippets(MemoryScope scope, List<SessionHistoryEntity> rows,
            int limit) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        List<MemorySnippet> out = new ArrayList<>(Math.min(rows.size(), limit));
        for (SessionHistoryEntity row : rows) {
            if (out.size() >= limit) break;
            String shortId = row.sessionId == null ? "?"
                    : (row.sessionId.length() > 8 ? row.sessionId.substring(0, 8) : row.sessionId);
            // value 不进 snippet——finalState 含 PII;DefaultPromptBuilder 仅拼 "[layer] key"。
            out.add(MemorySnippet.of(MemoryLayer.EPISODIC, scope,
                    "session#" + shortId + (row.finalState == null ? "" : ":" + row.finalState),
                    ""));
        }
        return Collections.unmodifiableList(out);
    }

    /** 测试用——清空缓存。 */
    public void invalidateCache() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private static final class CacheEntry {
        final List<MemorySnippet> full;
        final long createdAtMs;

        CacheEntry(List<MemorySnippet> full, long createdAtMs) {
            this.full = full;
            this.createdAtMs = createdAtMs;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAtMs > CACHE_TTL_MS;
        }

        List<MemorySnippet> truncate(int limit) {
            if (full.size() <= limit) return full;
            return Collections.unmodifiableList(new ArrayList<>(full.subList(0, limit)));
        }
    }
}
