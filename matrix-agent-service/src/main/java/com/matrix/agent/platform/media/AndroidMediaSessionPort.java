package com.matrix.agent.platform.media;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;

import java.util.List;

/** Selects only a unique active session owned by the requested fixed package. */
public final class AndroidMediaSessionPort implements MediaSessionPort {
    private final MediaSessionManager manager;

    public AndroidMediaSessionPort(Context context) {
        manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    @Override public Session find(MediaApp app) throws MediaPlatformException {
        if (manager == null) throw new MediaPlatformException("MEDIA_CONTROL_UNAVAILABLE");
        final List<MediaController> controllers;
        try {
            controllers = manager.getActiveSessions(null);
        } catch (SecurityException denied) {
            throw new MediaPlatformException("MEDIA_CONTROL_UNAVAILABLE");
        }
        MediaController match = null;
        for (MediaController controller : controllers) {
            if (!app.packageName().equals(controller.getPackageName())) continue;
            if (match != null) throw new MediaPlatformException("AMBIGUOUS_SESSION");
            match = controller;
        }
        if (match == null) throw new MediaPlatformException("NO_ACTIVE_SESSION");
        return new AndroidSession(match);
    }

    private static final class AndroidSession implements Session {
        private final MediaController controller;

        AndroidSession(MediaController controller) { this.controller = controller; }

        @Override public Snapshot snapshot() {
            PlaybackState playback = controller.getPlaybackState();
            MediaMetadata metadata = controller.getMetadata();
            long queueId = playback == null ? -1L : playback.getActiveQueueItemId();
            String title = metadata == null ? null
                    : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata == null ? null
                    : metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String mediaId = metadata == null ? null
                    : metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            long duration = metadata == null ? -1L
                    : metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            return new Snapshot(stateName(playback), playback == null ? 0L : playback.getActions(),
                    title, artist, mediaId, queueId,
                    playback == null ? -1L : playback.getPosition(),
                    duration);
        }

        @Override public void send(Action action, long positionMs) {
            MediaController.TransportControls transport = controller.getTransportControls();
            switch (action) {
                case PLAY: transport.play(); break;
                case PAUSE: transport.pause(); break;
                case NEXT: transport.skipToNext(); break;
                case PREVIOUS: transport.skipToPrevious(); break;
                case SEEK: transport.seekTo(positionMs); break;
                default: throw new IllegalArgumentException("unsupported media action");
            }
        }

        private static String stateName(PlaybackState playback) {
            if (playback == null) return "UNKNOWN";
            switch (playback.getState()) {
                case PlaybackState.STATE_PLAYING: return "PLAYING";
                case PlaybackState.STATE_PAUSED: return "PAUSED";
                case PlaybackState.STATE_BUFFERING: return "BUFFERING";
                case PlaybackState.STATE_STOPPED: return "STOPPED";
                case PlaybackState.STATE_CONNECTING: return "CONNECTING";
                case PlaybackState.STATE_ERROR: return "ERROR";
                case PlaybackState.STATE_NONE: return "NONE";
                default: return "OTHER";
            }
        }
    }
}
