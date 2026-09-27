package com.matrix.agent.host.rpc;

import android.app.KeyguardManager;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManagerGlobal;
import android.os.*;
import android.view.*;
import androidx.annotation.RequiresApi;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.api.interaction.*;
import com.matrix.agent.interaction.PrimaryTouchTracker;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** A leased, Launcher-only copy of touch input. Never pilfers, injects, records or logs coordinates. */
@RequiresApi(35)
public final class OverlayInteractionServiceStub extends IOverlayInteractionService.Stub implements AutoCloseable {
    private static final long LEASE_MS = 30_000, MOVE_INTERVAL_MS = 33;
    private final Context context;
    private final HandlerThread thread = new HandlerThread("matrix-overlay-pointer");
    private final Handler handler;
    private final AtomicReference<Registration> registration = new AtomicReference<>();
    private final DisplayManager displays;
    private final PrimaryTouchTracker tracker = new PrimaryTouchTracker();
    private Registration owner;
    private InputMonitor monitor;
    private InputEventReceiver receiver;
    private OverlayPointerSample pendingMove;
    private long lastDelivery;
    private int displayRotation;
    private volatile boolean closed;
    private final Runnable flush = () -> {
        var sample = pendingMove; pendingMove = null;
        if (sample != null) deliver(sample);
    };
    private final Runnable expire = this::checkExpiry;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) { clearRegistration(); }
    };
    private final DisplayManager.DisplayListener displayChanges = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { if (id == Display.DEFAULT_DISPLAY) clearRegistration(); }
        @Override public void onDisplayChanged(int id) {
            if (id != Display.DEFAULT_DISPLAY) return;
            Display display = displays.getDisplay(id);
            if (display != null && display.getRotation() != displayRotation) {
                displayRotation = display.getRotation();
                emit(tracker.cancel(SystemClock.uptimeMillis()));
            }
        }
    };
    private final class Registration {
        final IOverlayPointerCallback callback;
        final IBinder binder;
        final IBinder.DeathRecipient death;
        volatile long deadline = SystemClock.uptimeMillis() + LEASE_MS;
        Registration(IOverlayPointerCallback callback) {
            this.callback = callback; binder = callback.asBinder();
            death = () -> remove(this);
        }
    }
    public OverlayInteractionServiceStub(Context context) {
        this.context = context.getApplicationContext();
        displays = context.getSystemService(DisplayManager.class);
        thread.start(); handler = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_BACKGROUND);
        androidx.core.content.ContextCompat.registerReceiver(context, screen, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        displays.registerDisplayListener(displayChanges, handler);
    }
    private void authorize() {
        CallerContext caller = CallerContext.capture(context);
        caller.enforceTrusted(context);
        String[] packages = context.getPackageManager().getPackagesForUid(caller.uid);
        if (caller.userId != android.os.Process.myUid() / 100_000 || packages == null
                || !Arrays.asList(packages).contains(MatrixServiceConstants.LAUNCHER_PACKAGE)) {
            throw new SecurityException("Pointer observation requires Launcher in the serving user");
        }
    }
    @Override public synchronized void subscribe(IOverlayPointerCallback callback) {
        authorize();
        if (closed || callback == null) return;
        Registration previous = registration.get();
        if (previous != null && previous.binder.equals(callback.asBinder())) {
            previous.deadline = SystemClock.uptimeMillis() + LEASE_MS;
            handler.post(this::checkExpiry);
            return;
        }
        clearRegistration();
        Registration next = new Registration(callback);
        registration.set(next);
        try { next.binder.linkToDeath(next.death, 0); }
        catch (RemoteException dead) { remove(next); return; }
        handler.post(() -> start(next));
    }
    @Override public synchronized void unsubscribe(IOverlayPointerCallback callback) {
        authorize();
        Registration current = registration.get();
        if (current != null && callback != null && current.binder.equals(callback.asBinder())) remove(current);
    }
    private boolean unlocked() {
        return context.getSystemService(PowerManager.class).isInteractive()
                && !context.getSystemService(KeyguardManager.class).isDeviceLocked();
    }
    private void start(Registration next) {
        if (closed || registration.get() != next) return;
        stop(); owner = next;
        try {
            if (!unlocked()) { remove(next); return; }
            Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) { remove(next); return; }
            displayRotation = display.getRotation();
            // The runtime is compiled against the target ROM. The monitor only receives a copy;
            // calling pilferPointers would cancel other apps' gestures and is deliberately absent.
            monitor = InputManagerGlobal.getInstance().monitorGestureInput("MatrixPetLook", Display.DEFAULT_DISPLAY);
            receiver = new InputEventReceiver(monitor.getInputChannel(), handler.getLooper()) {
                @Override public void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent motion
                                && motion.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) receive(motion);
                    } finally { finishInputEvent(event, false); }
                }
            };
            next.callback.onAvailabilityChanged(true);
            checkExpiry();
        } catch (RuntimeException | RemoteException unavailable) { remove(next); }
    }
    private void receive(MotionEvent motion) {
        if (owner == null || registration.get() != owner) return;
        if (SystemClock.uptimeMillis() >= owner.deadline || !unlocked()) { remove(owner); return; }
        Display display = displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return; // The input channel is bound to DEFAULT_DISPLAY.
        int count = motion.getPointerCount();
        int[] ids = new int[count]; float[] xs = new float[count], ys = new float[count];
        for (int i = 0; i < count; i++) {
            ids[i] = motion.getPointerId(i); xs[i] = motion.getRawX(i); ys[i] = motion.getRawY(i);
        }
        emit(tracker.accept(motion.getActionMasked(), motion.getActionIndex(), motion.getDownTime(),
                motion.getEventTime(), ids, xs, ys, display.getRotation()));
    }
    private void emit(OverlayPointerSample sample) {
        if (sample == null) return;
        handler.removeCallbacks(flush);
        pendingMove = null;
        long now = SystemClock.uptimeMillis();
        if (sample.phase() == OverlayPointerSample.MOVE && now - lastDelivery < MOVE_INTERVAL_MS) {
            pendingMove = sample;
            handler.postDelayed(flush, MOVE_INTERVAL_MS - (now - lastDelivery));
        } else deliver(sample);
    }
    private void deliver(OverlayPointerSample sample) {
        Registration current = owner;
        if (current == null || registration.get() != current) return;
        if (SystemClock.uptimeMillis() >= current.deadline || !unlocked()) { remove(current); return; }
        try { current.callback.onPointer(sample); lastDelivery = SystemClock.uptimeMillis(); }
        catch (RemoteException dead) { remove(current); }
    }
    private void checkExpiry() {
        handler.removeCallbacks(expire);
        Registration current = owner;
        if (current == null || registration.get() != current) return;
        long remaining = current.deadline - SystemClock.uptimeMillis();
        if (remaining <= 0) remove(current);
        else handler.postDelayed(expire, remaining);
    }
    private void clearRegistration() {
        Registration old = registration.get();
        if (old != null) remove(old);
    }
    private void remove(Registration old) {
        if (!registration.compareAndSet(old, null)) return;
        try { old.binder.unlinkToDeath(old.death, 0); } catch (java.util.NoSuchElementException ignored) { }
        handler.post(() -> { if (owner == old) stop(); });
    }
    private void stop() {
        handler.removeCallbacks(flush); handler.removeCallbacks(expire);
        pendingMove = null; tracker.cancel(SystemClock.uptimeMillis());
        if (receiver != null) { receiver.dispose(); receiver = null; }
        if (monitor != null) { monitor.dispose(); monitor = null; }
        Registration old = owner; owner = null;
        if (old != null) try { old.callback.onAvailabilityChanged(false); } catch (RemoteException ignored) { }
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; clearRegistration();
        context.unregisterReceiver(screen); displays.unregisterDisplayListener(displayChanges);
        handler.post(() -> { stop(); thread.quitSafely(); });
    }
}
