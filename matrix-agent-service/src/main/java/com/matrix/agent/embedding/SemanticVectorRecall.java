package com.matrix.agent.embedding;

import com.matrix.agent.data.memory.MemoryScope;
import java.util.List;

@FunctionalInterface
public interface SemanticVectorRecall {
    SemanticVectorRecall NONE = (scope, query) -> List.of();
    record Hit(String key, double cosine) { }
    List<Hit> recall(MemoryScope scope, String query);
}
