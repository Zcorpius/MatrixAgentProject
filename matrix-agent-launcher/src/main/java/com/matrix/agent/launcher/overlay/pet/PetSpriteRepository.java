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
 * Application-owned, bounded PNG cache shared by characters and committed/preparing windows.
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
    public interface Subscription extends AutoCloseable { @Override void close(); }
    interface Decoder { Clip decode(Motion motion) throws Exception; }
    interface CharacterDecoder { Clip decode(PetCharacter character, Motion motion) throws Exception; }

    private record SpriteKey(PetCharacter character, Motion motion) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor worker;
    private final CharacterDecoder decoder;
    private final java.util.HashMap<SpriteKey, List<Request>> pending = new java.util.HashMap<>();
    private final LruCache<SpriteKey, Clip> cache = new LruCache<>(CACHE_BYTES) {
        @Override protected int sizeOf(SpriteKey key, Clip clip) { return clip.bytes(); }
    };
    private boolean closed;

    public PetSpriteRepository(AssetManager assets, Executor worker) {
        this(worker, new AssetDecoder(assets));
    }

    PetSpriteRepository(Executor worker, Decoder decoder) {
        this(worker, (character, motion) -> decoder.decode(motion));
    }

    PetSpriteRepository(Executor worker, CharacterDecoder decoder) {
        this.worker = worker;
        this.decoder = decoder;
    }

    public void warmUp() {
        warmUp(PetCharacter.YUKINO);
    }

    public void warmUp(PetCharacter character) {
        load(character, Motion.NEUTRAL, ignored -> {});
        load(character, Motion.IDLE, ignored -> {});
    }

    Clip cached(PetCharacter character, Motion motion) { return cache.get(new SpriteKey(character, motion)); }
    Clip cached(Motion motion) { return cached(PetCharacter.YUKINO, motion); }

    @androidx.annotation.VisibleForTesting
    int cachedBytes() { return cache.size(); }

    Subscription load(Motion motion, Consumer<Result> callback) {
        return load(PetCharacter.YUKINO, motion, callback);
    }

    /** Small public projection for settings thumbnails; callers never receive cache-owned clips. */
    public Subscription loadPreview(PetCharacter character, Consumer<Bitmap> callback) {
        return load(character, Motion.IDLE, result -> callback.accept(
                result.clip() == null ? null : result.clip().frames().get(0)));
    }

    Subscription load(PetCharacter character, Motion motion, Consumer<Result> callback) {
        SpriteKey key = new SpriteKey(character, motion);
        Request request = new Request(callback);
        if (closed) {
            main.post(() -> request.deliver(new Result(null, new IllegalStateException("Sprite repository closed"))));
            return request;
        }
        Clip cached = cache.get(key);
        if (cached != null) {
            main.post(() -> request.deliver(new Result(cached, null)));
            return request;
        }
        List<Request> waiting = pending.get(key);
        if (waiting != null) {
            waiting.add(request);
            return request;
        }
        pending.put(key, new ArrayList<>(List.of(request)));
        try {
            worker.execute(() -> {
                Result result;
                try { result = new Result(decoder.decode(character, motion), null); }
                catch (Exception failure) { result = new Result(null, failure); }
                Result completed = result;
                main.post(() -> deliver(key, completed));
            });
        } catch (RejectedExecutionException failure) {
            main.post(() -> deliver(key, new Result(null, failure)));
        }
        return request;
    }

    private void deliver(SpriteKey key, Result result) {
        if (closed) return;
        List<Request> waiting = pending.remove(key);
        if (result.clip() != null) cache.put(key, result.clip());
        else Log.w("MatrixPet", "Cannot load " + key.character().assetDirectory() + "/" + key.motion().assetKey(),
                result.failure());
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
    private static final class AssetDecoder implements CharacterDecoder {
        private final AssetManager assets;
        private final java.util.EnumMap<PetCharacter, PetSpriteCatalog> catalogs = new java.util.EnumMap<>(PetCharacter.class);

        AssetDecoder(AssetManager assets) { this.assets = assets; }

        @Override public Clip decode(PetCharacter character, Motion motion) throws Exception {
            PetSpriteCatalog catalog = catalogs.get(character);
            if (catalog == null) {
                try (var input = assets.open(character.assetDirectory() + "/animations.json")) {
                    var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                    catalog = PetSpriteCatalog.parse(
                            reader.lines().collect(java.util.stream.Collectors.joining("\n")), character);
                    catalogs.put(character, catalog);
                }
            }
            List<PetSpriteCatalog.Frame> definition = catalog.animations().get(motion.assetKey());
            List<Bitmap> frames = new ArrayList<>(definition.size());
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            for (var frame : definition) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Sprite load interrupted");
                try (var input = assets.open(character.assetDirectory() + "/" + frame.file())) {
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
