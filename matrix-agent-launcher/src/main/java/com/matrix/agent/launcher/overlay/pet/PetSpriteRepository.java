package com.matrix.agent.launcher.overlay.pet;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.MainThread;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import com.matrix.agent.launcher.overlay.pet.PetPresentation.Motion;

/**
 * Application-owned, bounded PNG cache shared by committed and preparing overlay windows.
 * Requests and delivery belong to main; decoding and catalog parsing belong to one worker lane.
 * Eviction drops references rather than recycling bitmaps that a visible window may still use.
 */
@MainThread
public final class PetSpriteRepository implements AutoCloseable {
    static final int CACHE_BYTES = 3 * 1024 * 1024;
    record Clip(List<Bitmap> frames, SpriteTimeline timeline) {
        Clip { frames = List.copyOf(frames); }
        int bytes() { return frames.stream().mapToInt(Bitmap::getAllocationByteCount).sum(); }
    }
    record Result(Clip clip, Exception failure) {}
    interface Subscription extends AutoCloseable { @Override void close(); }
    interface Decoder { Clip decode(Motion motion) throws Exception; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor worker;
    private final Decoder decoder;
    private final EnumMap<Motion, List<Request>> pending = new EnumMap<>(Motion.class);
    private final LruCache<Motion, Clip> cache = new LruCache<>(CACHE_BYTES) {
        @Override protected int sizeOf(Motion key, Clip clip) { return clip.bytes(); }
    };
    private boolean closed;

    public PetSpriteRepository(AssetManager assets, Executor worker) {
        this(worker, new AssetDecoder(assets));
    }

    PetSpriteRepository(Executor worker, Decoder decoder) {
        this.worker = worker;
        this.decoder = decoder;
    }

    public void warmUp() {
        load(Motion.NEUTRAL, ignored -> {});
        load(Motion.IDLE, ignored -> {});
    }

    Clip cached(Motion motion) { return cache.get(motion); }

    @androidx.annotation.VisibleForTesting
    int cachedBytes() { return cache.size(); }

    Subscription load(Motion motion, Consumer<Result> callback) {
        Request request = new Request(callback);
        if (closed) {
            main.post(() -> request.deliver(new Result(null, new IllegalStateException("Sprite repository closed"))));
            return request;
        }
        Clip cached = cache.get(motion);
        if (cached != null) {
            main.post(() -> request.deliver(new Result(cached, null)));
            return request;
        }
        List<Request> waiting = pending.get(motion);
        if (waiting != null) {
            waiting.add(request);
            return request;
        }
        pending.put(motion, new ArrayList<>(List.of(request)));
        try {
            worker.execute(() -> {
                Result result;
                try { result = new Result(decoder.decode(motion), null); }
                catch (Exception failure) { result = new Result(null, failure); }
                Result completed = result;
                main.post(() -> deliver(motion, completed));
            });
        } catch (RejectedExecutionException failure) {
            main.post(() -> deliver(motion, new Result(null, failure)));
        }
        return request;
    }

    private void deliver(Motion motion, Result result) {
        if (closed) return;
        List<Request> waiting = pending.remove(motion);
        if (result.clip() != null) cache.put(motion, result.clip());
        else Log.w("MatrixPet", "Cannot load " + motion.assetKey(), result.failure());
        if (waiting != null) waiting.forEach(request -> request.deliver(result));
    }

    public void trimMemory() { cache.evictAll(); }

    @Override public void close() {
        closed = true;
        pending.values().forEach(requests -> requests.forEach(Request::close));
        pending.clear();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }

    private static final class Request implements Subscription {
        private Consumer<Result> callback;
        Request(Consumer<Result> callback) { this.callback = callback; }
        void deliver(Result result) {
            Consumer<Result> receiver = callback;
            callback = null;
            if (receiver != null) receiver.accept(result);
        }
        @Override public void close() { callback = null; }
    }

    /** Only the serial decoding executor accesses this lazy catalog. */
    private static final class AssetDecoder implements Decoder {
        private final AssetManager assets;
        private PetSpriteCatalog catalog;

        AssetDecoder(AssetManager assets) { this.assets = assets; }

        @Override public Clip decode(Motion motion) throws Exception {
            if (catalog == null) {
                try (var input = assets.open("yukino/animations.json")) {
                    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                    catalog = PetSpriteCatalog.parse(reader.lines().collect(java.util.stream.Collectors.joining("\n")));
                }
            }
            List<PetSpriteCatalog.Frame> definition = catalog.animations().get(motion.assetKey());
            List<Bitmap> frames = new ArrayList<>(definition.size());
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            for (var frame : definition) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Sprite load interrupted");
                try (var input = assets.open("yukino/" + frame.file())) {
                    Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                    if (bitmap == null || bitmap.getWidth() != catalog.width() || bitmap.getHeight() != catalog.height()) {
                        throw new IOException("Invalid sprite: " + frame.file());
                    }
                    frames.add(bitmap);
                }
            }
            return new Clip(frames, new SpriteTimeline(definition.stream().map(PetSpriteCatalog.Frame::durationMs)
                    .collect(java.util.stream.Collectors.toList())));
        }
    }
}
