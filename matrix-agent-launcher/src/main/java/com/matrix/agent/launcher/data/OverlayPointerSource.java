package com.matrix.agent.launcher.data;

import com.matrix.agent.api.interaction.OverlayPointerSample;
import java.util.function.Consumer;

/** Main-thread, visibility-scoped observation. Closing invalidates queued samples immediately. */
public interface OverlayPointerSource {
    interface Observation extends AutoCloseable { @Override void close(); }
    Observation observe(Consumer<OverlayPointerSample> pointer, Runnable unavailable);
    OverlayPointerSource NONE = (pointer, unavailable) -> () -> {};
}
