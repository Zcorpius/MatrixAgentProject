package com.matrix.agent.launcher.overlay.pet;

import static org.junit.Assert.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Motion.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Indicator.*;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.launcher.LauncherApplication;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Actual WindowManager/Canvas/Looper, with deterministic decoder completion ordering. */
@RunWith(AndroidJUnit4.class)
public final class YukinoPetDeviceTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final List<Runnable> decoding = new ArrayList<>();
    private PetSpriteRepository sprites;
    private YukinoPetView pet;
    private FrameLayout root;
    private WindowManager windows;

    @Before public void create() {
        main(() -> {
            var app = (LauncherApplication) instrumentation.getTargetContext().getApplicationContext();
            app.overlay().dismiss();
            sprites = new PetSpriteRepository(decoding::add, (PetPresentation.Motion motion) -> {
                assertNotEquals("Decode must run off main", Looper.getMainLooper(), Looper.myLooper());
                return switch (motion) {
                    case NEUTRAL -> clip(Color.GRAY);
                    case IDLE -> clip(Color.GREEN, Color.CYAN);
                    case WORKING -> clip(Color.BLUE, Color.MAGENTA);
                    case SUCCEEDED -> clip(Color.YELLOW, Color.WHITE);
                    case FAILED -> clip(Color.RED, Color.BLACK);
                    case RUN_LEFT -> clip(Color.rgb(255, 128, 0), Color.rgb(255, 192, 0));
                    case RUN_RIGHT -> clip(Color.rgb(128, 0, 255), Color.rgb(192, 0, 255));
                    default -> clip(Color.DKGRAY);
                };
            });
            var display = app.getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY);
            var context = app.createDisplayContext(display);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                context = context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            }
            windows = context.getSystemService(WindowManager.class);
            root = new FrameLayout(context);
            pet = new YukinoPetView(context, sprites);
            root.addView(pet, new FrameLayout.LayoutParams(-1, -1));
            var layout = new WindowManager.LayoutParams(192, 208, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.TOP | Gravity.LEFT;
            layout.x = 40; layout.y = 350;
            pet.setPlaybackEnabled(true);
            windows.addView(root, layout);
        });
        instrumentation.waitForIdleSync();
    }

    @After public void close() {
        main(() -> {
            pet.close();
            windows.removeViewImmediate(root);
            sprites.close();
        });
    }

    @Test public void lateLoadsCannotReplaceNewStateAndCloseCancelsSubscribers() {
        main(() -> {
            pet.present("one", new PetPresentation(WORKING, NONE));
            pet.present("one", new PetPresentation(FAILED, ERROR));
        });
        runDecode(3); // Failure completes before work, idle, and neutral.
        await(() -> snapshot().motion() == FAILED && snapshot().advancing());
        assertEquals(Color.RED, centerPixel());
        runDecode(2); // Old working result must not change the displayed failure.
        runDecode(1);
        runDecode(0);
        assertEquals(FAILED, snapshot().motion());
        await(() -> !snapshot().advancing());
        assertEquals(Color.BLACK, centerPixel());
        main(() -> {
            pet.present("two", new PetPresentation(REVIEW, UNCERTAIN));
            pet.close();
        });
        runDecode(0);
        assertFalse(snapshot().advancing());
    }

    @Test public void completedGesturePlaysOncePerRoundAndPersistentBadgeSurvivesIdle() {
        runDecode(1); // Cache idle and allow its loop to start.
        runDecode(0);
        main(() -> pet.present("one", new PetPresentation(SUCCEEDED, SUCCESS)));
        runDecode(0);
        assertEquals(Color.YELLOW, centerPixel());
        await(() -> snapshot().motion() == IDLE);
        main(() -> pet.present("one", new PetPresentation(SUCCEEDED, SUCCESS)));
        assertEquals(IDLE, snapshot().motion());
        main(() -> pet.present("two", new PetPresentation(SUCCEEDED, SUCCESS)));
        assertEquals(SUCCEEDED, snapshot().motion());
        assertEquals(Color.YELLOW, centerPixel());
    }

    @Test public void disablingHidingAndDetachingPauseWithoutResettingFrame() {
        runDecode(1);
        runDecode(0);
        await(() -> snapshot().frame() == 1);
        main(() -> pet.setPlaybackEnabled(false));
        var frozen = snapshot();
        assertFalse(frozen.advancing());
        SystemClock.sleep(500);
        assertEquals(frozen, snapshot());
        main(() -> pet.setPlaybackEnabled(true));
        assertTrue(snapshot().advancing());
        main(() -> root.setVisibility(View.INVISIBLE));
        assertFalse(snapshot().advancing());
        SystemClock.sleep(500);
        main(() -> root.setVisibility(View.VISIBLE));
        assertTrue(snapshot().advancing());
        main(() -> root.removeView(pet));
        assertFalse(snapshot().advancing());
        main(() -> root.addView(pet));
        await(() -> snapshot().advancing());
    }

    @Test public void repeatedPresentationsDoNotResetRunningAnimation() {
        main(() -> pet.present("one", new PetPresentation(WORKING, NONE)));
        runDecode(2);
        await(() -> snapshot().frame() == 1);
        main(() -> {
            pet.present("one", new PetPresentation(WORKING, NONE));
            assertEquals(1, pet.playbackSnapshot().frame());
        });
    }

    @Test public void subscribersShareDecodedFramesAndCacheEvictionDoesNotRecycleVisibleBitmaps() {
        CompletableFuture<PetSpriteRepository.Result> first = new CompletableFuture<>(), second = new CompletableFuture<>();
        main(() -> {
            sprites.load(WORKING, first::complete);
            sprites.load(WORKING, second::complete);
            assertEquals(3, decoding.size()); // Neutral, idle, one shared work decode.
        });
        runDecode(2);
        var a = first.join().clip();
        assertSame(a, second.join().clip());
        main(sprites::trimMemory);
        assertFalse(a.frames().get(0).isRecycled());
    }

    @Test public void decodingFailureKeepsFallbackClickable() {
        runDecode(0); // A neutral poster is available while actions load.
        var broken = new AtomicReference<PetSpriteRepository>();
        main(() -> {
            broken.set(new PetSpriteRepository(decoding::add, (PetPresentation.Motion motion) -> {
                throw new java.io.IOException("fixture");
            }));
            pet.close();
            root.removeView(pet);
            pet = new YukinoPetView(root.getContext(), broken.get());
            root.addView(pet);
            pet.setOnClickListener(view -> root.setTag("clicked"));
            pet.setPlaybackEnabled(true);
        });
        // The old idle request and both failed new loads all complete after replacement.
        while (!decoding.isEmpty()) runDecode(0);
        main(() -> {
            assertTrue(pet.performClick());
            assertEquals("clicked", root.getTag());
            assertFalse(pet.playbackSnapshot().advancing());
            broken.get().close();
        });
    }

    @Test public void realAssetsDecodeWithTransparencyAndBoundedCacheOnDevice() throws Exception {
        var app = (LauncherApplication) instrumentation.getTargetContext().getApplicationContext();
        for (var motion : PetPresentation.Motion.values()) {
            CompletableFuture<PetSpriteRepository.Result> loaded = new CompletableFuture<>();
            main(() -> app.petSprites().load(motion, loaded::complete));
            var result = loaded.get(5, TimeUnit.SECONDS);
            assertNull(result.failure());
            main(() -> assertTrue(app.petSprites().cachedBytes() <= PetSpriteRepository.CACHE_BYTES));
            for (var bitmap : result.clip().frames()) {
                assertEquals(192, bitmap.getWidth());
                assertEquals(208, bitmap.getHeight());
                assertTrue(bitmap.hasAlpha());
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)));
            }
        }
    }

    @Test public void draggingRestoresPausedTaskFrameAndDoesNotRestartOnEachMove() {
        main(() -> pet.present("one", new PetPresentation(WORKING, NONE)));
        runDecode(2);
        await(() -> snapshot().frame() == 1);
        main(() -> pet.drag(true));
        runDecode(2);
        await(() -> snapshot().motion() == RUN_LEFT && snapshot().frame() == 1);
        main(() -> {
            pet.drag(true);
            assertEquals(1, pet.playbackSnapshot().frame());
            pet.endDrag();
            assertEquals(WORKING, pet.playbackSnapshot().motion());
            assertEquals(1, pet.playbackSnapshot().frame());
        });
    }

    @Test public void taskUpdatesDuringDragWaitForReleaseAndOldDirectionLoadsStayDiscarded() {
        runDecode(1);
        runDecode(0);
        main(() -> {
            pet.drag(true);
            pet.drag(false);
            pet.present("next", new PetPresentation(FAILED, ERROR));
            assertEquals(RUN_RIGHT, pet.playbackSnapshot().motion());
        });
        runDecode(1); // Right wins even if the old left load finishes later.
        runDecode(0);
        assertEquals(RUN_RIGHT, snapshot().motion());
        main(pet::endDrag);
        runDecode(0);
        assertEquals(FAILED, snapshot().motion());
        await(() -> !snapshot().advancing());
        main(() -> { pet.drag(false); pet.endDrag(); });
        assertEquals(FAILED, snapshot().motion());
        assertFalse(snapshot().advancing()); // A stopped failure animation stays stopped after dragging.
    }

    @Test public void draggingACompletedPetDoesNotReplayCelebrationAndDisableClearsOverride() {
        runDecode(1);
        runDecode(0);
        main(() -> pet.present("one", new PetPresentation(SUCCEEDED, SUCCESS)));
        runDecode(0);
        await(() -> snapshot().motion() == IDLE);
        main(() -> pet.drag(true));
        runDecode(0);
        main(() -> {
            pet.endDrag();
            assertEquals(IDLE, pet.playbackSnapshot().motion());
            pet.drag(true);
            pet.setPlaybackEnabled(false);
            assertEquals(IDLE, pet.playbackSnapshot().motion());
            assertFalse(pet.playbackSnapshot().advancing());
            pet.setPlaybackEnabled(true);
            assertEquals(IDLE, pet.playbackSnapshot().motion());
        });
    }

    @Test public void unfinishedCelebrationResumesAfterDragAndLateLoadsCannotRestoreRunning() {
        runDecode(1);
        runDecode(0);
        main(() -> pet.present("one", new PetPresentation(SUCCEEDED, SUCCESS)));
        runDecode(0);
        await(() -> snapshot().frame() == 1);
        main(() -> { pet.drag(true); pet.endDrag(); }); // Release before the run sequence finishes loading.
        assertEquals(SUCCEEDED, snapshot().motion());
        assertEquals(1, snapshot().frame());
        runDecode(0);
        await(() -> snapshot().motion() == IDLE);
    }

    private PetSpriteRepository.Clip clip(int... colors) {
        List<Bitmap> frames = new ArrayList<>();
        List<Integer> durations = new ArrayList<>();
        for (int color : colors) {
            Bitmap bitmap = Bitmap.createBitmap(192, 208, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(color);
            frames.add(bitmap); durations.add(180);
        }
        return new PetSpriteRepository.Clip(frames, new SpriteTimeline(durations));
    }

    private void runDecode(int index) {
        AtomicReference<Runnable> work = new AtomicReference<>();
        main(() -> work.set(decoding.remove(index)));
        work.get().run();
        instrumentation.waitForIdleSync();
    }

    private YukinoPetView.PlaybackSnapshot snapshot() {
        AtomicReference<YukinoPetView.PlaybackSnapshot> state = new AtomicReference<>();
        main(() -> state.set(pet.playbackSnapshot()));
        return state.get();
    }

    private int centerPixel() {
        AtomicReference<Integer> pixel = new AtomicReference<>();
        main(() -> {
            Bitmap image = Bitmap.createBitmap(pet.getWidth(), pet.getHeight(), Bitmap.Config.ARGB_8888);
            pet.draw(new Canvas(image));
            pixel.set(image.getPixel(image.getWidth() / 2, image.getHeight() / 2));
            image.recycle();
        });
        return pixel.get();
    }

    private void await(BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 3000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(10);
        assertTrue("Pet transition timed out", condition.getAsBoolean());
    }

    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
}
