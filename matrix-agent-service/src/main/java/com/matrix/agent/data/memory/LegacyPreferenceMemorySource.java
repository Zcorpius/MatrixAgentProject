package com.matrix.agent.data.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Preference Memory 默认实现——桥接旧版 {@link MemoryStore}。
 *
 * <p>调 {@link MemoryStore#getAllPreferences(MemoryScope)} 取当前 occupant scope 的偏好,按 Map 迭代顺序
 * （当前易失实现保持插入顺序）取前 maxItems 条。
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
        Map<String, String> prefs = memoryStore.getAllPreferences(scope);
        if (prefs.isEmpty()) return Collections.emptyList();
        List<MemorySnippet> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : prefs.entrySet()) {
            if (result.size() >= maxItems) break;
            result.add(new MemorySnippet(MemoryLayer.PREFERENCE, scope,
                    entry.getKey(), entry.getValue(), 1.0, 0L, null));
        }
        return Collections.unmodifiableList(result);
    }
}
