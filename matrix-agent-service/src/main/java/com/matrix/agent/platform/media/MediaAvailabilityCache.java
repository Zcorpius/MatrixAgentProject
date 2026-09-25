package com.matrix.agent.platform.media;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking availability hints. Prompt construction reads memory only; Android Binder calls
 * run on the injected I/O executor. A session listener is scoped to recent media requests.
 */
public final class MediaAvailabilityCache implements MediaAvailabilitySource, AutoCloseable {
    private static final long SESSION_TTL_MS = 5_000L;
    private static final long IDLE_LISTENER_MS = 30_000L;
    private final Context context;
    private final PackageProbe packages;
    private final MediaSessionManager manager;
    private final Executor executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final MediaSessionManager.OnActiveSessionsChangedListener listener =
            controllers -> scheduleRefresh();
    private volatile MediaAvailabilitySnapshot snapshot = MediaAvailabilitySnapshot.unknown();
    private volatile long lastInterestElapsed;
    private volatile boolean listening;
    private volatile boolean closed;

    public MediaAvailabilityCache(Context context, PackageProbe packages, Executor executor) {
        this.context = context.getApplicationContext();
        this.packages = packages;
        this.executor = executor;
        this.manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    /** Called for text-recognised media intent; never blocks the prompt thread on a Binder call. */
    @Override public MediaAvailabilitySnapshot peekAndWarm() {
        lastInterestElapsed = SystemClock.elapsedRealtime();
        if (shouldRefresh(snapshot, System.currentTimeMillis())) {
            scheduleRefresh();
        }
        mainHandler.post(this::ensureListening);
        return snapshot;
    }

    static boolean shouldRefresh(MediaAvailabilitySnapshot snapshot, long nowMillis) {
        long captured = snapshot.capturedAtMillis();
        return captured <= 0L || nowMillis < captured
                || nowMillis - captured >= SESSION_TTL_MS;
    }

    private void scheduleRefresh() {
        if (closed || !refreshing.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    EnumMap<MediaApp, Boolean> installed =
                            new EnumMap<>(MediaApp.class);
                    EnumMap<MediaApp, Integer> sessionCounts = activeSessionCounts();
                    for (MediaApp app : MediaApp.values()) {
                        installed.put(app, packages.installedAndEnabled(app));
                    }
                    snapshot = MediaAvailabilitySnapshot.fromFacts(installed, sessionCounts,
                            System.currentTimeMillis());
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (RuntimeException rejected) {
            refreshing.set(false);
        }
    }

    private EnumMap<MediaApp, Integer> activeSessionCounts() {
        if (manager == null) return null;
        try {
            List<MediaController> controllers = manager.getActiveSessions(null);
            EnumMap<MediaApp, Integer> counts = new EnumMap<>(MediaApp.class);
            for (MediaApp app : MediaApp.values()) counts.put(app, 0);
            for (MediaController controller : controllers) {
                for (MediaApp app : MediaApp.values()) {
                    if (app.packageName().equals(controller.getPackageName())) {
                        counts.put(app, counts.get(app) + 1);
                    }
                }
            }
            return counts;
        } catch (SecurityException denied) {
            return null;
        }
    }

    private void ensureListening() {
        if (closed || manager == null) return;
        if (!listening && context.checkSelfPermission("android.permission.MEDIA_CONTENT_CONTROL")
                == PackageManager.PERMISSION_GRANTED) {
            try {
                manager.addOnActiveSessionsChangedListener(listener, null, mainHandler);
                listening = true;
            } catch (SecurityException denied) {
                // A stale availability hint never grants execution permission.
            }
        }
        mainHandler.removeCallbacks(idleStop);
        mainHandler.postDelayed(idleStop, IDLE_LISTENER_MS);
    }

    private final Runnable idleStop = new Runnable() {
        @Override public void run() {
            if (SystemClock.elapsedRealtime() - lastInterestElapsed < IDLE_LISTENER_MS) {
                mainHandler.postDelayed(this, IDLE_LISTENER_MS);
                return;
            }
            stopListening();
        }
    };

    private void stopListening() {
        if (!listening || manager == null) return;
        manager.removeOnActiveSessionsChangedListener(listener);
        listening = false;
    }

    @Override public void close() {
        closed = true;
        mainHandler.removeCallbacks(idleStop);
        mainHandler.post(this::stopListening);
    }
}
