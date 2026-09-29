package com.matrix.agent.identity;

/** Provides a trusted runtime profile for a newly constructed task. */
@FunctionalInterface
public interface RuntimeProfileSource {
    RuntimeProfile snapshot();

    RuntimeProfileSource UNKNOWN = () -> RuntimeProfile.UNKNOWN;
}
