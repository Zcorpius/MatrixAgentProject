package com.matrix.agent.client;

/**
 * Small, Android-free state machine that guarantees one death-recipient link per connection
 * generation. Keeping the reservation before the Binder call makes discovery and explicit-bind
 * callbacks linearizable and lets the race semantics be covered by ordinary JVM tests.
 */
final class DeathLinkCoordinator<T> {
    private T inFlight;
    private T linked;
    private long inFlightGeneration = -1L;

    synchronized boolean reserve(T candidate, long generation, long currentGeneration,
            boolean released) {
        if (candidate == null || released || generation != currentGeneration) return false;
        if (linked != null || inFlight != null) return false;
        inFlight = candidate;
        inFlightGeneration = generation;
        return true;
    }

    synchronized boolean promote(T candidate, long generation, long currentGeneration,
            boolean released) {
        boolean current = inFlight == candidate && inFlightGeneration == generation
                && !released && generation == currentGeneration;
        clearReservation(candidate);
        if (current) linked = candidate;
        return current;
    }

    synchronized void abandon(T candidate) {
        if (linked == candidate) linked = null;
        clearReservation(candidate);
    }

    synchronized T takeLinked() {
        T value = linked;
        linked = null;
        return value;
    }

    synchronized T takeInFlight() {
        T value = inFlight;
        inFlight = null;
        inFlightGeneration = -1L;
        return value;
    }

    private void clearReservation(T candidate) {
        if (inFlight != candidate) return;
        inFlight = null;
        inFlightGeneration = -1L;
    }
}
