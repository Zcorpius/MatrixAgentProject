package com.matrix.agent.download;

import androidx.annotation.Nullable;

/** Implemented by the process Application to expose the download domain's narrow runtime view. */
public interface DownloadRuntimeProvider {
    @Nullable DownloadRuntime downloadRuntime();
}
