package com.matrix.agent.launcher.overlay.pet;

import static org.junit.Assert.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Motion.*;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.WindowInspector;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationRuntimeStage;
import com.matrix.agent.api.handoff.HandoffProtocol;
import com.matrix.agent.api.interaction.OverlayPointerSample;
import com.matrix.agent.launcher.LauncherApplication;
import com.matrix.agent.launcher.data.LauncherHostGateway;
import com.matrix.agent.launcher.data.OverlayPointerClient;
import com.matrix.agent.launcher.data.OverlayPointerSource;
import com.matrix.agent.launcher.overlay.OverlayConversationPresenter;
import com.matrix.agent.launcher.overlay.OverlayWindow;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real platform Host -> published SDK -> Launcher -> Window, over the system app drawer. */
@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 35)
public final class GlobalPetInteractionDeviceTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiAutomation ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    private LauncherApplication app;
    private LauncherHostGateway.ConnectionLease lease;
    private OverlayPointerClient pointers;
    private OverlayPointerSource.Observation observation;
    private OverlayWindow window;
    private YukinoPetView pet;
    private final AtomicReference<OverlayPointerSample> last = new AtomicReference<>();
    private long downTime;

    @Before public void create() throws Exception {
        var info = ui.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(info);
        main(() -> {
            app = (LauncherApplication) instrumentation.getTargetContext().getApplicationContext();
            app.overlay().dismiss();
            lease = app.hostGateway().acquireConnection();
        });
        await(() -> app.hostGateway().isConnected());
        // A test process has no foreground Activity. Shell launch avoids Android's background
        // activity-launch denial, and Home permits overlays whereas secure Settings hides them.
        shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME");
        await(() -> desktop(node -> true) != null);
        SystemClock.sleep(350);
        Rect screen = app.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, screen.centerX(), screen.height() * .9f);
        for (int i = 1; i <= 12; i++) {
            send(MotionEvent.ACTION_MOVE, screen.centerX(), screen.height() * (.9f - .06f * i));
            SystemClock.sleep(20);
        }
        send(MotionEvent.ACTION_UP, screen.centerX(), screen.height() * .18f);
        await(() -> desktop(node -> android.text.TextUtils.equals("Matrix AI", node.getText())) != null);
        main(() -> {
            pointers = new OverlayPointerClient(app.hostGateway(), app.executorRegistry());
            window = new OverlayWindow(app, new Actions(), app.petSprites(), ignored -> {});
            window.render(new OverlayConversationPresenter.State("注视与挥手验证", "任务进行中", List.of(),
                    ConversationMessage.STATUS_RUNNING, true, false, "", false, false, "", "gaze-round",
                    ConversationRuntimeStage.STAGE_EXECUTING), "", HandoffProtocol.IDLE, false);
            window.attach(false);
            for (var root : WindowInspector.getGlobalWindowViews()) {
                pet = findPet(root);
                if (pet != null) break;
            }
            assertNotNull(pet);
            observation = pointers.observe(sample -> { last.set(sample); window.pointer(sample); }, window::cancelLook);
        });
        await(this::monitorPresent);
        await(() -> snapshot().motion() == WORKING && snapshot().advancing());
    }

    @After public void close() {
        main(() -> {
            if (observation != null) observation.close();
            if (pointers != null) pointers.close();
            if (window != null) window.close();
            if (lease != null) lease.close();
        });
        await(() -> !monitorPresent());
    }

    @Test public void crossAppSwipeUpdatesGazeWithoutStealingDrawerDismissGesture() throws Exception {
        String before = desktopText();
        Rect bounds = app.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
        int x = bounds.centerX(), start = bounds.top + 320, end = bounds.bottom - 350;
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, x, start);
        await(() -> snapshot().motion() == LOOK && !snapshot().advancing());
        int initialDirection = snapshot().frame();
        screenshot("01-look-before-swipe");
        for (int i = 1; i <= 15; i++) {
            send(MotionEvent.ACTION_MOVE, x, start + (end - start) * i / 15f);
            SystemClock.sleep(25);
        }
        await(() -> snapshot().frame() != initialDirection);
        screenshot("02-look-after-swipe");
        send(MotionEvent.ACTION_UP, x, end);
        await(() -> last.get() != null && last.get().phase() == OverlayPointerSample.UP);
        assertEquals(LOOK, snapshot().motion());
        await(() -> !before.equals(desktopText()));
        await(() -> snapshot().motion() == WORKING);
        screenshot("03-task-resumed");
        main(window::wave);
        await(() -> snapshot().motion() == WAVING && snapshot().advancing());
        screenshot("04-wave");
        await(() -> snapshot().motion() == WORKING);
    }

    @Test public void crossAppTapOpensSettingsIconAndInterruptsWave() {
        Predicate<AccessibilityNodeInfo> settingsIcon = node -> android.text.TextUtils.equals("设置", node.getText())
                || android.text.TextUtils.equals("Settings", node.getText());
        for (int i = 0; i < 8 && desktop(settingsIcon) == null; i++) {
            var list = desktop(AccessibilityNodeInfo::isScrollable);
            assertNotNull(list);
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            SystemClock.sleep(250);
        }
        var entry = desktop(settingsIcon);
        assertNotNull("System app drawer must expose Settings", entry);
        Rect area = new Rect(); entry.getBoundsInScreen(area);
        main(window::wave);
        await(() -> snapshot().motion() == WAVING);
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, area.centerX(), area.centerY());
        await(() -> snapshot().motion() == LOOK);
        send(MotionEvent.ACTION_UP, area.centerX(), area.centerY());
        await(() -> {
            var root = ui.getRootInActiveWindow();
            return root != null && "com.android.settings".contentEquals(root.getPackageName());
        });
        await(() -> snapshot().motion() == WORKING);
    }

    @Test public void liftingFirstFingerDoesNotTransferGazeToSecondFinger() {
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, 180, 1500);
        await(() -> snapshot().motion() == LOOK);
        multi(MotionEvent.ACTION_POINTER_DOWN | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, true, 180, 1500);
        multi(MotionEvent.ACTION_MOVE, true, 180, 1300);
        multi(MotionEvent.ACTION_POINTER_UP, true, 180, 1300);
        await(() -> last.get() != null && last.get().phase() == OverlayPointerSample.UP);
        long releasedAt = last.get().uptimeMillis();
        multi(MotionEvent.ACTION_MOVE, false, 200, 500);
        multi(MotionEvent.ACTION_UP, false, 200, 500);
        await(() -> snapshot().motion() == WORKING);
        assertEquals(releasedAt, last.get().uptimeMillis());
    }

    @Test public void closingObservationRemovesMonitorAndLateInputCannotChangePet() {
        main(() -> { observation.close(); observation = null; window.cancelLook(); });
        await(() -> !monitorPresent());
        last.set(null);
        downTime = SystemClock.uptimeMillis();
        send(MotionEvent.ACTION_DOWN, 200, 1200);
        send(MotionEvent.ACTION_CANCEL, 200, 1200);
        SystemClock.sleep(200);
        assertNull(last.get());
        assertEquals(WORKING, snapshot().motion());
    }

    @Test public void hostExpiresAbandonedLeaseWithoutDependingOnLauncherCallbacks() throws Exception {
        main(() -> { observation.close(); observation = null; });
        await(() -> !monitorPresent());
        var result = app.hostGateway().callOnCurrentThread(agent -> agent.getOverlayInteractionManager());
        assertTrue(result.isSuccess()); assertNotNull(result.value);
        var ready = new java.util.concurrent.CompletableFuture<Boolean>();
        var ended = new java.util.concurrent.CompletableFuture<Boolean>();
        var callback = new com.matrix.agent.api.interaction.IOverlayPointerCallback.Stub() {
            @Override public void onPointer(OverlayPointerSample sample) { }
            @Override public void onAvailabilityChanged(boolean available) {
                (available ? ready : ended).complete(true);
            }
        };
        try {
            assertTrue(result.value.subscribe(callback));
            assertTrue(ready.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(monitorPresent());
            assertTrue(ended.get(35, java.util.concurrent.TimeUnit.SECONDS));
            await(() -> !monitorPresent());
        } finally { result.value.unsubscribe(callback); }
    }

    private void send(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        inject(event);
    }
    private void multi(int action, boolean primary, float x, float y) {
        int count = primary ? 2 : 1;
        var properties = new MotionEvent.PointerProperties[count];
        var coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = primary ? i : 1;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = i == 0 ? x : 300;
            coordinates[i].y = i == 0 ? y : 500;
            coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        inject(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, count, properties,
                coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0));
    }
    private void inject(MotionEvent event) {
        try { assertTrue(ui.injectInputEvent(event, true)); }
        finally { event.recycle(); }
        instrumentation.waitForIdleSync();
    }
    private AccessibilityNodeInfo desktop(Predicate<AccessibilityNodeInfo> predicate) {
        for (var value : ui.getWindows()) {
            var root = value.getRoot();
            if (root != null && "com.android.launcher3".contentEquals(root.getPackageName())) {
                var found = visit(root, predicate);
                if (found != null) return found;
            }
        }
        return null;
    }
    private AccessibilityNodeInfo visit(AccessibilityNodeInfo node, Predicate<AccessibilityNodeInfo> predicate) {
        if (node.isVisibleToUser() && predicate.test(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            var child = node.getChild(i);
            if (child == null) continue;
            var result = visit(child, predicate);
            if (result != null) return result;
        }
        return null;
    }
    private String desktopText() {
        StringBuilder text = new StringBuilder();
        desktop(node -> { if (node.getText() != null) text.append(node.getText()).append('\n'); return false; });
        return text.toString();
    }
    private boolean monitorPresent() {
        try { return shell("dumpsys input").contains("MatrixPetLook"); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private String shell(String command) throws Exception {
        try (var descriptor = ui.executeShellCommand(command);
                var input = new FileInputStream(descriptor.getFileDescriptor())) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private YukinoPetView findPet(View node) {
        if (node instanceof YukinoPetView value) return value;
        if (node instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            var value = findPet(group.getChildAt(i)); if (value != null) return value;
        }
        return null;
    }
    private YukinoPetView.PlaybackSnapshot snapshot() {
        AtomicReference<YukinoPetView.PlaybackSnapshot> result = new AtomicReference<>();
        main(() -> result.set(pet.playbackSnapshot())); return result.get();
    }
    private void await(BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 8000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(30);
        assertTrue("Global pet interaction timed out", condition.getAsBoolean());
    }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private void screenshot(String name) throws Exception {
        SystemClock.sleep(150);
        File directory = new File(app.getExternalFilesDir(null), "yukino-interaction-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        var image = ui.takeScreenshot(); assertNotNull(image);
        try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
        } finally { image.recycle(); }
    }
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
