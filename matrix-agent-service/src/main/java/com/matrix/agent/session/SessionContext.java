package com.matrix.agent.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

public final class SessionContext {
    private static final int MAX_TURNS = 12;

    private final Deque<String> recentTurns = new ArrayDeque<>();
    private Integer lastTemperature;
    private String lastZone = "driver";

    public synchronized void addTurn(String text) {
        if (recentTurns.size() == MAX_TURNS) {
            recentTurns.removeFirst();
        }
        recentTurns.addLast(text);
    }

    public synchronized List<String> getRecentTurns() {
        return Collections.unmodifiableList(new ArrayList<>(recentTurns));
    }

    public synchronized Integer getLastTemperature() {
        return lastTemperature;
    }

    public synchronized String getLastZone() {
        return lastZone;
    }

    /** A single atomic, bounded state projection for Working memory. */
    public synchronized String verifiedClimateSnapshot() {
        if (lastTemperature == null || lastTemperature < 16 || lastTemperature > 30) return null;
        if (!"driver".equals(lastZone) && !"passenger".equals(lastZone)) return null;
        return lastZone + ":" + lastTemperature;
    }

    public synchronized void rememberTemperature(int temperature, String zone) {
        this.lastTemperature = temperature;
        this.lastZone = zone;
    }

    public synchronized void clear() {
        recentTurns.clear();
        lastTemperature = null;
        lastZone = "driver";
    }
}
