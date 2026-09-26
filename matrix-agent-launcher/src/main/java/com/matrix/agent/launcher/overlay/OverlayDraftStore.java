package com.matrix.agent.launcher.overlay;

import java.util.HashMap;
import java.util.Map;

/** Process-local overlay drafts never overwrite the full-page persisted draft. Main-thread owned. */
public final class OverlayDraftStore {
    public record Draft(String text, long revision) {}
    private final Map<String, Draft> drafts = new HashMap<>();
    public Draft get(String conversation) { return drafts.getOrDefault(conversation, new Draft("", 0)); }
    public void set(String conversation, String text) {
        Draft old = get(conversation);
        if (!old.text().equals(text)) drafts.put(conversation, new Draft(text, old.revision() + 1));
    }
    public boolean clearIfRevision(String conversation, long revision) {
        Draft old = get(conversation);
        if (old.revision() != revision) return false;
        drafts.put(conversation, new Draft("", revision + 1));
        return true;
    }
}
