package com.matrix.agent.launcher.presentation;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.api.media.MediaOutputSnapshot;
import com.matrix.agent.launcher.data.LauncherHostGateway;
import com.matrix.agent.launcher.data.MediaOutputRepository;

/** State shown by Settings; never treats a requested route as the confirmed route. */
public final class MediaOutputViewModel extends ViewModel {
    public record State(MediaOutputSnapshot snapshot, boolean connected, boolean busy,
            String notice) { }

    private final MediaOutputRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(null, false, false, ""));
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (connected) {
                if (subscription == null && !subscribing) subscribe();
                refresh();
            }
            main.postDelayed(this, 1_500);
        }
    };
    private AutoCloseable subscription;
    private boolean active;
    private boolean connected;
    private boolean subscribing;
    private int generation;
    private long lastRevision = -1;
    private int pendingOutput = MediaOutputSnapshot.UNKNOWN;
    private long pendingSince;

    public MediaOutputViewModel(MediaOutputRepository repository) { this.repository = repository; }
    public LiveData<State> state() { return state; }

    public void start() {
        if (active) return;
        active = true;
        main.post(poll);
        if (connected) subscribe();
    }

    public void stop() {
        active = false;
        generation++;
        subscribing = false;
        lastRevision = -1;
        main.removeCallbacks(poll);
        pendingOutput = MediaOutputSnapshot.UNKNOWN;
        state.setValue(new State(null, connected, false, ""));
        if (subscription != null) {
            repository.unsubscribe(subscription);
            subscription = null;
        }
    }

    public void setConnected(boolean value) {
        if (connected == value) return;
        connected = value;
        if (!value) {
            lastRevision = -1;
            pendingOutput = MediaOutputSnapshot.UNKNOWN;
        }
        State previous = state.getValue();
        state.setValue(new State(value ? previous.snapshot() : null, value, false,
                value ? "" : "Host 未连接，媒体输出暂不可用"));
        if (!value) {
            generation++;
            subscribing = false;
            if (subscription != null) {
                repository.unsubscribe(subscription);
                subscription = null;
            }
        } else if (active) {
            subscribe();
        }
    }

    private void subscribe() {
        if (subscription != null || subscribing || !active || !connected) return;
        subscribing = true;
        int expected = ++generation;
        repository.subscribe(snapshot -> {
            if (active && connected && generation == expected) update(snapshot);
        }, result -> {
            if (generation != expected || !active || !connected) {
                if (result.value != null) repository.unsubscribe(result.value);
                return;
            }
            subscribing = false;
            if (result.isSuccess()) {
                subscription = result.value;
            } else {
                notice("无法订阅媒体路由变化");
                refresh();
            }
        });
    }

    public void refresh() {
        if (!active || !connected) return;
        int expected = generation;
        repository.snapshot(result -> {
            if (!active || !connected || generation != expected) return;
            if (result.isSuccess() && result.value != null) update(result.value);
            else notice("无法读取当前媒体输出");
        });
    }

    public void select(int output) {
        State previous = state.getValue();
        if (!active || !connected || previous.busy() || previous.snapshot() == null
                || !available(previous.snapshot(), output)) return;
        state.setValue(new State(previous.snapshot(), true, true, "正在切换媒体输出…"));
        int expected = generation;
        repository.select(output, result -> {
            if (!active || !connected || generation != expected) return;
            if (!result.isSuccess() || result.value == null) {
                state.setValue(new State(state.getValue().snapshot(), true, false,
                        "媒体输出切换失败"));
                return;
            }
            MediaOutputSnapshot snapshot = result.value;
            if (snapshot.message.isEmpty() && snapshot.selected != output) {
                pendingOutput = output;
                pendingSince = SystemClock.elapsedRealtime();
            } else if (snapshot.message.startsWith("正在切换")) {
                pendingOutput = output;
                pendingSince = SystemClock.elapsedRealtime();
            } else {
                pendingOutput = MediaOutputSnapshot.UNKNOWN;
            }
            if (snapshot.revision >= lastRevision) {
                lastRevision = snapshot.revision;
                state.setValue(new State(snapshot, true, false, snapshot.message));
            } else {
                State latest = state.getValue();
                state.setValue(new State(latest.snapshot(), true, false, snapshot.message));
            }
            refresh();
        });
    }

    private void update(MediaOutputSnapshot snapshot) {
        if (snapshot.revision < lastRevision) return;
        lastRevision = snapshot.revision;
        State previous = state.getValue();
        String notice = previous.notice();
        if (pendingOutput != MediaOutputSnapshot.UNKNOWN) {
            if (snapshot.selected == pendingOutput) {
                pendingOutput = MediaOutputSnapshot.UNKNOWN;
                notice = "";
            } else if (SystemClock.elapsedRealtime() - pendingSince > 2_500) {
                pendingOutput = MediaOutputSnapshot.UNKNOWN;
                notice = "系统尚未切换到所选设备";
            } else {
                notice = "正在切换媒体输出…";
            }
        } else if (previous.snapshot() == null
                || snapshot.revision > previous.snapshot().revision) {
            notice = "";
        }
        state.setValue(new State(snapshot, true, previous.busy(),
                notice));
    }

    private void notice(String message) {
        State previous = state.getValue();
        state.setValue(new State(previous.snapshot(), connected, false, message));
    }

    private static boolean available(MediaOutputSnapshot snapshot, int output) {
        return switch (output) {
            case MediaOutputSnapshot.BLUETOOTH -> snapshot.bluetoothAvailable;
            case MediaOutputSnapshot.LOCAL_HEADSET -> snapshot.localHeadsetAvailable;
            case MediaOutputSnapshot.SPEAKER -> snapshot.speakerAvailable;
            default -> false;
        };
    }

    @Override protected void onCleared() {
        stop();
        super.onCleared();
    }
}
