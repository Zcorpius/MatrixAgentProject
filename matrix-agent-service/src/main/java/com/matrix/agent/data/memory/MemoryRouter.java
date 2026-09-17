package com.matrix.agent.data.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Memory 召回入口实现——按 layer 优先级合并 4 层 source。
 *
 * <p>优先级:Working > Episodic > Semantic > Preference。每层调用时传入剩余 slots 数,
 * 保证总数不超过 maxItems 且优先级高的层先填满。
 *
 * <p>不做相似度打分、token 预算、去重——这些留给后续版本。
 * 只确保 4 层接口稳定、能产出 MemorySnippet 列表给 PromptBuilder / LlmPlanner。
 */
public final class MemoryRouter implements MemoryRecaller {
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

        result.addAll(workingSource.recallWorking(scope, sessionId, userText, maxItems));
        if (result.size() < maxItems) {
            result.addAll(episodicSource.recallEpisodic(scope, userText, maxItems - result.size()));
        }
        if (result.size() < maxItems) {
            result.addAll(semanticSource.recallSemantic(scope, userText, maxItems - result.size()));
        }
        if (result.size() < maxItems) {
            result.addAll(preferenceSource.recallPreference(scope, userText, maxItems - result.size()));
        }

        if (result.size() > maxItems) {
            result = new ArrayList<>(result.subList(0, maxItems));
        }
        return Collections.unmodifiableList(result);
    }
}
