package com.matrix.agent.launcher.overlay.pet;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Process-local, bounded conversation history. Opening the panel does not count as leaving. */
public final class PetGreetingPolicy {
    private static final long RETURN_MS = 5 * 60_000L;
    private final LinkedHashMap<String, Long> lastLeft = new LinkedHashMap<>(64, .75f, true);
    private String visible;
    public boolean visibility(String conversation, long now) {
        if (Objects.equals(visible, conversation)) return false;
        if (visible != null) lastLeft.put(visible, now);
        visible = conversation;
        if (conversation == null) return false;
        Long left = lastLeft.get(conversation);
        boolean greet = left == null || now - left >= RETURN_MS;
        lastLeft.put(conversation, now);
        while (lastLeft.size() > 64) lastLeft.remove(lastLeft.keySet().iterator().next());
        return greet;
    }
}
