package com.matrix.agent.intent;

import com.matrix.agent.data.memory.MemoryKeyCatalog;

/** Detects an explicit request to persist memory using the shared memory vocabulary. */
public final class KeywordMemoryIntentDetector implements MemoryIntentDetector {
    public static final KeywordMemoryIntentDetector INSTANCE = new KeywordMemoryIntentDetector();

    @Override
    public boolean isExplicitMemorySave(String command) {
        return MemoryKeyCatalog.hasExplicitSaveIntent(command);
    }

    KeywordMemoryIntentDetector() { }
}
