package com.matrix.agent.platform.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import com.matrix.agent.api.media.MediaOutputSnapshot;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Single owner of Matrix's temporary MEDIA-strategy preference. All state and audio callbacks run
 * on one looper. A topology change releases Matrix's preference so Android's default routing wins.
 */
public final class MediaOutputController implements AutoCloseable {
    private static final String TAG = "MatrixMediaOutput";
    private static final String PREFS = "media_output";
    private static final String OWNED_TYPE = "owned_type";
    private static final String OWNED_ADDRESS = "owned_address";
    private static final String OWNED_TOPOLOGY = "owned_topology";
    private static final String RELEASE_PENDING = "release_pending";
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build();

    private final AudioManager audio;
    private final SystemMediaRouting routing;
    private final SharedPreferences preferences;
    private final HandlerThread thread = new HandlerThread("matrix-media-output");
    private final Handler handler;
    private final Consumer<MediaOutputSnapshot> observer;
    private final AudioDeviceCallback deviceCallback = new AudioDeviceCallback() {
        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added) { topologyChanged(); }
        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) { topologyChanged(); }
    };

    private AudioProductStrategy strategy;
    private Set<DeviceKey> knownDevices = Set.of();
    private DeviceKey ownedDevice;
    private String ownedTopology;
    private boolean releasePending;
    private MediaOutputSnapshot current;
    private long revision;
    private volatile boolean closed;

    private record DeviceKey(int type, String address) {
        static DeviceKey of(AudioDeviceInfo device) {
            return new DeviceKey(device.getType(), device.getAddress());
        }
        static DeviceKey of(AudioDeviceAttributes device) {
            return new DeviceKey(device.getType(), device.getAddress());
        }
    }

    public MediaOutputController(Context context, Consumer<MediaOutputSnapshot> observer) {
        audio = Objects.requireNonNull(context.getSystemService(AudioManager.class));
        routing = new SystemMediaRouting();
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.observer = observer;
        thread.start();
        handler = new Handler(thread.getLooper());
        run(this::initialize);
    }

    private void initialize() {
        strategy = routing.strategies().stream()
                .filter(candidate -> candidate.supportsAudioAttributes(MEDIA))
                .findFirst().orElse(null);
        knownDevices = connectedDevices();
        int type = preferences.getInt(OWNED_TYPE, AudioDeviceInfo.TYPE_UNKNOWN);
        if (type != AudioDeviceInfo.TYPE_UNKNOWN) {
            ownedDevice = new DeviceKey(type, preferences.getString(OWNED_ADDRESS, ""));
            ownedTopology = preferences.getString(OWNED_TOPOLOGY, "");
            releasePending = preferences.getBoolean(RELEASE_PENDING, false);
            reconcileOwnedPreference();
        }
        audio.registerAudioDeviceCallback(deviceCallback, handler);
        publish();
    }

    public MediaOutputSnapshot snapshot() {
        return run(() -> {
            topologyChanged();
            return current;
        });
    }

    public MediaOutputSnapshot select(int output) {
        return run(() -> selectOnWorker(output));
    }

    private MediaOutputSnapshot selectOnWorker(int output) {
        topologyChanged();
        if (output != MediaOutputSnapshot.BLUETOOTH
                && output != MediaOutputSnapshot.LOCAL_HEADSET
                && output != MediaOutputSnapshot.SPEAKER) {
            throw new IllegalArgumentException("Unknown media output " + output);
        }
        if (strategy == null) return failure("系统未提供媒体输出策略");
        AudioDeviceInfo target = Arrays.stream(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                .filter(device -> category(device.getType()) == output)
                .sorted((left, right) -> {
                    // Stable choice if the platform reports more than one device in a category.
                    int byAddress = left.getAddress().compareTo(right.getAddress());
                    return byAddress != 0 ? byAddress : Integer.compare(left.getType(), right.getType());
                })
                .findFirst().orElse(null);
        if (target == null) return failure("该输出设备尚未连接");
        DeviceKey key = DeviceKey.of(target);
        try {
            if (!routing.prefer(strategy, new AudioDeviceAttributes(target))) {
                return failure("系统未接受媒体输出切换");
            }
            ownedDevice = key;
            ownedTopology = topologyKey(connectedDevices());
            releasePending = false;
            preferences.edit().putInt(OWNED_TYPE, key.type())
                    .putString(OWNED_ADDRESS, key.address())
                    .putString(OWNED_TOPOLOGY, ownedTopology)
                    .remove(RELEASE_PENDING).apply();
            publish();
            // Audio policy and the HAL can settle after the synchronous role update.
            handler.postDelayed(this::publish, 120);
            handler.postDelayed(this::publish, 500);
            return current.selected == output ? current
                    : withMessage(current, "正在切换媒体输出…");
        } catch (RuntimeException failure) {
            Log.w(TAG, "Unable to select output", failure);
            return failure("媒体输出切换失败");
        }
    }

    private void topologyChanged() {
        Set<DeviceKey> now = connectedDevices();
        boolean changed = !now.equals(knownDevices);
        if (changed) {
            if (ownedDevice != null) markReleasePending();
            knownDevices = now;
        }
        if (changed || (releasePending && knownDevices.contains(ownedDevice))) {
            reconcileOwnedPreference();
        }
        publish();
    }

    private void reconcileOwnedPreference() {
        if (ownedDevice == null) return;
        if (!topologyKey(knownDevices).equals(ownedTopology)) markReleasePending();
        if (strategy == null) return;
        try {
            List<AudioDeviceAttributes> preferredDevices = routing.preferred(strategy);
            AudioDeviceAttributes preferred = preferredDevices.size() == 1
                    ? preferredDevices.get(0) : null;
            if (!preferredDevices.isEmpty()
                    && (preferred == null || !ownedDevice.equals(DeviceKey.of(preferred)))) {
                // Another controller changed the role. Never clear its preference.
                forgetOwnership();
                return;
            }
            if (!releasePending) {
                if (preferred == null) forgetOwnership();
                return;
            }
            if (preferred == null && knownDevices.contains(ownedDevice)) {
                // The owned device is connected, so an empty role means it was removed elsewhere.
                forgetOwnership();
                return;
            }
            // Audio policy may reject removal while the preferred device is disconnected.
            // Some ROMs also lose their applied-role cache across a Bluetooth reconnection
            // while retaining the saved role. Reapply our still-owned role before retrying.
            if (routing.remove(strategy) || releaseAfterReapply(preferred)) forgetOwnership();
            else Log.w(TAG, "Audio service rejected preference removal; will retry");
        } catch (RuntimeException failure) {
            Log.w(TAG, "Cannot reconcile media route; will retry", failure);
        }
    }

    private boolean releaseAfterReapply(AudioDeviceAttributes preferred) {
        if (preferred == null || !knownDevices.contains(ownedDevice)) return false;
        AudioDeviceInfo connected = Arrays.stream(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
                .filter(device -> ownedDevice.equals(DeviceKey.of(device)))
                .findFirst().orElse(null);
        return connected != null
                && routing.prefer(strategy, new AudioDeviceAttributes(connected))
                && routing.remove(strategy);
    }

    private void markReleasePending() {
        if (releasePending) return;
        releasePending = true;
        preferences.edit().putBoolean(RELEASE_PENDING, true).apply();
    }

    private void forgetOwnership() {
        ownedDevice = null;
        ownedTopology = null;
        releasePending = false;
        preferences.edit().remove(OWNED_TYPE).remove(OWNED_ADDRESS)
                .remove(OWNED_TOPOLOGY).remove(RELEASE_PENDING).apply();
    }

    private static String topologyKey(Set<DeviceKey> devices) {
        return devices.stream().map(device -> device.type() + ":" + device.address())
                .sorted().collect(Collectors.joining("\n"));
    }

    private Set<DeviceKey> connectedDevices() {
        Set<DeviceKey> devices = new HashSet<>();
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (category(device.getType()) != MediaOutputSnapshot.UNKNOWN) {
                devices.add(DeviceKey.of(device));
            }
        }
        return devices;
    }

    private void publish() {
        if (closed) return;
        Set<DeviceKey> available = connectedDevices();
        int selected = MediaOutputSnapshot.UNKNOWN;
        try {
            List<AudioDeviceAttributes> routed = routing.routed(MEDIA);
            if (routed.size() == 1) selected = category(routed.get(0).getType());
        } catch (RuntimeException failure) {
            Log.w(TAG, "Cannot query media route", failure);
        }
        MediaOutputSnapshot next = new MediaOutputSnapshot(revision + 1, selected,
                hasCategory(available, MediaOutputSnapshot.BLUETOOTH),
                hasCategory(available, MediaOutputSnapshot.LOCAL_HEADSET),
                hasCategory(available, MediaOutputSnapshot.SPEAKER), "");
        if (sameState(current, next)) return;
        revision++;
        current = next;
        observer.accept(next);
    }

    private static boolean sameState(MediaOutputSnapshot first, MediaOutputSnapshot second) {
        return first != null && first.selected == second.selected
                && first.bluetoothAvailable == second.bluetoothAvailable
                && first.localHeadsetAvailable == second.localHeadsetAvailable
                && first.speakerAvailable == second.speakerAvailable;
    }

    private static boolean hasCategory(Set<DeviceKey> devices, int category) {
        return devices.stream().anyMatch(device -> category(device.type()) == category);
    }

    private MediaOutputSnapshot failure(String message) {
        publish();
        return withMessage(current, message);
    }

    private static MediaOutputSnapshot withMessage(MediaOutputSnapshot state, String message) {
        return new MediaOutputSnapshot(state.revision, state.selected, state.bluetoothAvailable,
                state.localHeadsetAvailable, state.speakerAvailable, message);
    }

    public static int category(int type) {
        return switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET ->
                    MediaOutputSnapshot.BLUETOOTH;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY -> MediaOutputSnapshot.LOCAL_HEADSET;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> MediaOutputSnapshot.SPEAKER;
            default -> MediaOutputSnapshot.UNKNOWN;
        };
    }

    private <T> T run(Callable<T> task) {
        if (Looper.myLooper() == handler.getLooper()) {
            try { return task.call(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
        FutureTask<T> future = new FutureTask<>(task);
        if (!handler.post(future)) throw new IllegalStateException("Media output controller closed");
        try { return future.get(3, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new IllegalStateException("Media output unavailable", failure); }
    }

    private void run(Runnable task) { run(() -> { task.run(); return null; }); }

    @Override public void close() {
        if (closed) return;
        run(() -> {
            if (closed) return;
            closed = true;
            audio.unregisterAudioDeviceCallback(deviceCallback);
            thread.quitSafely();
        });
    }
}
