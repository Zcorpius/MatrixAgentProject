package com.matrix.agent.platform.media;

/** Package facts are checked at execution time, outside PolicyEngine. */
public interface PackageProbe {
    boolean installedAndEnabled(MediaApp app);
}
