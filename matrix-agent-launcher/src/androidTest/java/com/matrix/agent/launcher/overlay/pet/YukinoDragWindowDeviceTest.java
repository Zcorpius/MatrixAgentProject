package com.matrix.agent.launcher.overlay.pet;

import static org.junit.Assert.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Motion.*;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationRuntimeStage;
import com.matrix.agent.api.handoff.HandoffProtocol;
import com.matrix.agent.launcher.LauncherApplication;
import com.matrix.agent.launcher.overlay.OverlayConversationPresenter;
import com.matrix.agent.launcher.overlay.OverlayWindow;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real input-dispatcher gestures on the production overlay, including reversal and cancellation. */
@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
public final class YukinoDragWindowDeviceTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiAutomation ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    private OverlayWindow window;
    private YukinoPetView pet;
    private long downTime;
    private float x, y;

    @Before public void create() {
        main(() -> {
            var app = (LauncherApplication) instrumentation.getTargetContext().getApplicationContext();
            app.overlay().dismiss();
            window = new OverlayWindow(app, new Actions(), app.petSprites(), ignored -> {});
            window.render(state(ConversationMessage.STATUS_RUNNING), "", HandoffProtocol.IDLE, false);
            window.attach(false);
        });
        instrumentation.waitForIdleSync();
        main(() -> {
            for (var root : WindowInspector.getGlobalWindowViews()) {
                pet = findPet(root);
                if (pet != null) break;
            }
            assertNotNull(pet);
        });
        await(() -> snapshot().motion() == WORKING && snapshot().advancing());
    }

    @After public void close() { main(window::close); }

    @Test public void draggingTurnsLeftAndRightAndReleaseRestoresTaskWithoutOpeningPanel() throws Exception {
        down();
        send(MotionEvent.ACTION_MOVE, x - 260, y);
        await(() -> snapshot().motion() == RUN_LEFT && snapshot().advancing());
        screenshot("01-drag-left");
        send(MotionEvent.ACTION_MOVE, x + 1, y);
        assertEquals(RUN_LEFT, snapshot().motion());
        send(MotionEvent.ACTION_MOVE, x + 100, y);
        await(() -> snapshot().motion() == RUN_RIGHT && snapshot().advancing());
        screenshot("02-drag-right");
        send(MotionEvent.ACTION_UP, x, y);
        await(() -> snapshot().motion() == WORKING && snapshot().advancing());
        main(() -> assertFalse(window.expanded()));
        screenshot("03-released");
    }

    @Test public void pointerCancelRestoresLatestTaskAndSmallTapStillExpandsPanel() {
        down();
        send(MotionEvent.ACTION_MOVE, x - 160, y);
        await(() -> snapshot().motion() == RUN_LEFT);
        main(() -> window.render(state(ConversationMessage.STATUS_FAILED), "", HandoffProtocol.IDLE, false));
        assertEquals(RUN_LEFT, snapshot().motion());
        send(MotionEvent.ACTION_CANCEL, x, y);
        await(() -> snapshot().motion() == FAILED);
        main(() -> assertFalse(window.expanded()));
        down();
        send(MotionEvent.ACTION_MOVE, x + 1, y + 1);
        assertEquals(FAILED, snapshot().motion());
        send(MotionEvent.ACTION_UP, x, y);
        main(() -> assertTrue(window.expanded()));
        assertFalse(snapshot().advancing());
    }

    @Test public void hidingDuringDragCannotLeaveRunningOverrideAfterReveal() {
        down();
        send(MotionEvent.ACTION_MOVE, x - 180, y);
        await(() -> snapshot().motion() == RUN_LEFT);
        main(() -> window.setHidden(true));
        assertEquals(WORKING, snapshot().motion());
        assertFalse(snapshot().advancing());
        send(MotionEvent.ACTION_CANCEL, x, y);
        main(() -> window.setHidden(false));
        await(() -> snapshot().motion() == WORKING && snapshot().advancing());
    }

    private OverlayConversationPresenter.State state(int status) {
        return new OverlayConversationPresenter.State("雪乃拖动验证", "任务状态验证", List.of(), status,
                true, false, "", false, false, "", "drag-round", ConversationRuntimeStage.STAGE_EXECUTING);
    }

    private void down() {
        main(() -> {
            int[] location = new int[2];
            pet.getLocationOnScreen(location);
            x = location[0] + pet.getWidth() / 2f;
            y = location[1] + pet.getHeight() / 2f;
        });
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x, y);
    }

    private void send(int action, float nextX, float nextY) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, nextX, nextY, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(ui.injectInputEvent(event, true)); }
        finally { event.recycle(); }
        x = nextX; y = nextY;
        instrumentation.waitForIdleSync();
    }

    private void screenshot(String name) throws Exception {
        // A loaded frame is not yet compositor evidence. Allow traversal/presentation after the
        // async decoder callback before capturing the real display (animation remains running).
        SystemClock.sleep(200);
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "yukino-drag-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap image = ui.takeScreenshot();
        assertNotNull(image);
        try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }

    private YukinoPetView findPet(View node) {
        if (node instanceof YukinoPetView value) return value;
        if (node instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            var value = findPet(group.getChildAt(i));
            if (value != null) return value;
        }
        return null;
    }

    private YukinoPetView.PlaybackSnapshot snapshot() {
        AtomicReference<YukinoPetView.PlaybackSnapshot> result = new AtomicReference<>();
        main(() -> result.set(pet.playbackSnapshot()));
        return result.get();
    }

    private void await(BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 5000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(10);
        assertTrue("Drag presentation timed out", condition.getAsBoolean());
    }

    private void main(Runnable action) { instrumentation.runOnMainSync(action); }

    private static final class Actions implements OverlayWindow.Actions {
        @Override public void draftChanged(String text) {}
        @Override public void loadOlderMessages() {}
        @Override public void send() {}
        @Override public void cancel() {}
        @Override public void returnToAgent() {}
        @Override public void dismiss() {}
        @Override public void changed() {}
        @Override public void windowFailed(RuntimeException failure) { throw failure; }
    }
}
