package com.matrix.agent.contract;

@FunctionalInterface
public interface ModelStreamSink {
    ModelStreamSink NONE = event -> { };
    void accept(ModelStreamEvent event);
}
