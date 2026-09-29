package com.matrix.agent.contract;

/** Ephemeral generation events. Only BodyDelta is eligible for public display. */
public sealed interface ModelStreamEvent {
    record BodyDelta(String text) implements ModelStreamEvent { }
    record ToolArgumentsDelta(int index, String fragment) implements ModelStreamEvent { }
    record Completed(FinishReason reason) implements ModelStreamEvent { }
    record Failed() implements ModelStreamEvent { }
}
