package com.matrix.agent.platform.media;

/** Presentation is optional; cancellation and the operation deadline are not. */
public interface ExternalAppHandoffPort {
    interface Operation extends AutoCloseable { @Override void close(); }
    ExternalAppHandoffPort NONE = new ExternalAppHandoffPort() {};
    default Operation begin(LaunchContext context) { return () -> {}; }
    default void prepare(LaunchContext context, MediaApp app, int reason)
            throws MediaPlatformException { context.checkActive(); }
    default void launchFinished(LaunchContext context, int result) {}
}
