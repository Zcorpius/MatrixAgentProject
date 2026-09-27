package com.matrix.agent.platform.media;

/** Small synchronous boundary around a single, package-matched Android media session. */
public interface MediaSessionPort {
    Session find(MediaApp app) throws MediaPlatformException;

    enum Action { PLAY, PAUSE, NEXT, PREVIOUS, SEEK }

    interface Session {
        Snapshot snapshot();
        void send(Action action, long positionMs);
    }

    final class Snapshot {
        public final String playbackState;
        public final long actions;
        public final String title;
        public final String artist;
        public final String mediaId;
        public final long queueItemId;
        public final long positionMs;
        public final long durationMs;

        public Snapshot(String playbackState, long actions, String title, String mediaId,
                long queueItemId, long positionMs, long durationMs) {
            this(playbackState, actions, title, null, mediaId, queueItemId, positionMs, durationMs);
        }

        public Snapshot(String playbackState, long actions, String title, String artist,
                String mediaId, long queueItemId, long positionMs, long durationMs) {
            this.playbackState = playbackState;
            this.actions = actions;
            this.title = title;
            this.artist = artist;
            this.mediaId = mediaId;
            this.queueItemId = queueItemId;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }
}
