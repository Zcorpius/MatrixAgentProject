package com.matrix.agent.data.memory;

import java.util.List;

/**
 * Preference Memory 层——用户偏好(memory.preference.save / get)。
 *
 * <p>默认实现 {@link LegacyPreferenceMemorySource} 包装旧版 {@link MemoryStore#getAllPreferences},
 * 行为与旧版完全一致——保证 Preference 层不引入回归。
 */
public interface PreferenceMemorySource {
    List<MemorySnippet> recallPreference(MemoryScope scope, String userText, int maxItems);
}
