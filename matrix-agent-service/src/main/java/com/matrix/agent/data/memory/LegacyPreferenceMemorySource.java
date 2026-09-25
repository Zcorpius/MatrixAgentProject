package com.matrix.agent.data.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Preference Memory 默认实现——桥接旧版 {@link MemoryStore}。
 *
 * <p>保留存储层的时间与来源元数据，按问题相关性、显式保存和更新时间排序。
 *
 * <p>行为兼容旧版 LlmPlanner.savedKeysFor——返回的 snippet.key 与原"已保存的偏好 key 列表"一致。
 *
 * <p><b>双维度隔离</b>:Preference 查询和写入均使用 {@code (userId, zone)}。保留的
 * user-only {@link MemoryStore} API 显式落在 {@code GLOBAL} scope，不会混入 DRIVER/PASSENGER。
 */
public final class LegacyPreferenceMemorySource implements PreferenceMemorySource {
    private final MemoryStore memoryStore;

    public LegacyPreferenceMemorySource(MemoryStore memoryStore) {
        if (memoryStore == null) throw new IllegalArgumentException("memoryStore 不能为空");
        this.memoryStore = memoryStore;
    }

    @Override
    public List<MemorySnippet> recallPreference(MemoryScope scope, String userText, int maxItems) {
        if (maxItems <= 0) return Collections.emptyList();
        List<PreferenceRecord> prefs = memoryStore.getPreferenceRecords(scope);
        if (prefs.isEmpty()) return Collections.emptyList();
        List<MemorySnippet> result = new ArrayList<>();
        List<PreferenceRecord> ranked = new ArrayList<>(prefs);
        ranked.removeIf(entry -> !MemoryKeyCatalog.isPreferenceKey(entry.key()));
        ranked.sort((a, b) -> {
            int relevance = Integer.compare(MemoryKeyCatalog.relevance(b.key(), userText),
                    MemoryKeyCatalog.relevance(a.key(), userText));
            if (relevance != 0) return relevance;
            int origin = Boolean.compare(b.explicit(), a.explicit());
            if (origin != 0) return origin;
            int time = Long.compare(b.capturedAtMs(), a.capturedAtMs());
            return time != 0 ? time : a.key().compareTo(b.key());
        });
        for (PreferenceRecord entry : ranked) {
            if (result.size() >= maxItems) break;
            result.add(new MemorySnippet(MemoryLayer.PREFERENCE, scope,
                    entry.key(), entry.value(), 1.0, entry.capturedAtMs(), null));
        }
        return Collections.unmodifiableList(result);
    }
}
