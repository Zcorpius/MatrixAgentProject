package com.matrix.agent.data.memory;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Combines useful memory layers with a preference reserve and independent failure boundaries. */
public final class MemoryRouter implements MemoryRecaller {
    private static final String TAG = "MatrixAgent";
    private final WorkingMemorySource workingSource;
    private final EpisodicMemorySource episodicSource;
    private final SemanticMemorySource semanticSource;
    private final PreferenceMemorySource preferenceSource;

    public MemoryRouter(WorkingMemorySource working, EpisodicMemorySource episodic,
            SemanticMemorySource semantic, PreferenceMemorySource preference) {
        if (working == null) throw new IllegalArgumentException("working source 不能为空");
        if (episodic == null) throw new IllegalArgumentException("episodic source 不能为空");
        if (semantic == null) throw new IllegalArgumentException("semantic source 不能为空");
        if (preference == null) throw new IllegalArgumentException("preference source 不能为空");
        this.workingSource = working;
        this.episodicSource = episodic;
        this.semanticSource = semantic;
        this.preferenceSource = preference;
    }

    @Override
    public List<MemorySnippet> recall(MemoryScope scope, String sessionId, String userText, int maxItems) {
        if (scope == null || maxItems <= 0) return Collections.emptyList();
        List<MemorySnippet> result = new ArrayList<>();

        // Fetch preferences once so other layers can borrow unused slots and preferences can
        // fill the remainder without another storage read or unstable second ranking.
        List<MemorySnippet> preferences = recallSafely(MemoryLayer.PREFERENCE, () ->
                preferenceSource.recallPreference(scope, userText, maxItems));
        append(result, Math.min(maxItems, 3), preferences, 0);
        append(result, maxItems, recallSafely(MemoryLayer.WORKING, () ->
                workingSource.recallWorking(scope, sessionId, userText,
                        Math.min(maxItems - result.size(), 1))), 0);
        append(result, maxItems, recallSafely(MemoryLayer.SEMANTIC, () -> semanticSource.recallSemantic(scope, userText,
                Math.min(maxItems - result.size(), 4))), 0);
        append(result, maxItems, recallSafely(MemoryLayer.EPISODIC, () -> episodicSource.recallEpisodic(scope, userText,
                Math.min(maxItems - result.size(), 2))), 0);
        append(result, maxItems, preferences, Math.min(preferences.size(), 3));

        return Collections.unmodifiableList(result);
    }

    private static List<MemorySnippet> recallSafely(MemoryLayer layer,
            java.util.function.Supplier<List<MemorySnippet>> source) {
        try {
            List<MemorySnippet> items = source.get();
            if (items == null) return Collections.emptyList();
            List<MemorySnippet> usable = new ArrayList<>(items.size());
            for (MemorySnippet item : items) {
                if (item != null && MemoryKeyCatalog.promptKey(item.getLayer(), item.getKey()) != null
                        && (item.getLayer() != MemoryLayer.WORKING
                                || MemoryKeyCatalog.workingValueForPrompt(
                                        item.getKey(), item.getValue()) != null)) {
                    usable.add(item);
                }
            }
            return usable;
        } catch (RuntimeException failure) {
            // One optional source may fail while already-recalled layers remain usable.
            Log.w(TAG, "[MemoryRouter] recall layer=" + layer.wireValue()
                    + " failed cause=" + failure.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private static void append(List<MemorySnippet> target, int maxItems,
            List<MemorySnippet> items, int start) {
        for (int i = start; i < items.size() && target.size() < maxItems; i++) {
            MemorySnippet item = items.get(i);
            target.add(item);
        }
    }
}
