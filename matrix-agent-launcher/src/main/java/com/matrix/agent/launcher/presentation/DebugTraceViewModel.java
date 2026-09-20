package com.matrix.agent.launcher.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.client.DebugTraceManager;
import com.matrix.agent.launcher.data.LauncherHostGateway;

import java.util.ArrayList;
import java.util.List;

/** 调试轨迹页状态：回放 + 实时合并为单一升序列表。 */
public final class DebugTraceViewModel extends ViewModel {

    public static final class State {
        public final boolean connected;
        public final List<DebugTraceWireEvent> events;

        State(boolean connected, List<DebugTraceWireEvent> events) {
            this.connected = connected;
            this.events = events;
        }
    }

    private final LauncherHostGateway gateway;
    private final MutableLiveData<State> state =
            new MutableLiveData<>(new State(false, List.of()));
    private final List<DebugTraceWireEvent> events = new ArrayList<>();
    private boolean subscribed;

    public DebugTraceViewModel(LauncherHostGateway gateway) {
        this.gateway = gateway;
    }

    public LiveData<State> state() { return state; }

    public void start() {
        if (subscribed) return;
        subscribed = true;
        gateway.execute(agent -> {
            DebugTraceManager manager = agent.getDebugTraceManager();
            if (manager == null) return null;
            return manager.subscribe(event -> {
                // 回调已在 SDK eventHandler（主线程）派发
            });
        }, result -> {
            if (result.isSuccess() && result.value != null) {
                events.clear();
                events.addAll(result.value);
                publish(true);
            } else {
                publish(false);
            }
        });
        // 实时事件：直接观察 Manager 的 buffer（SDK 主线程桥接）
        gateway.execute(agent -> {
            DebugTraceManager manager = agent.getDebugTraceManager();
            if (manager == null) return null;
            manager.subscribe(new DebugTraceManager.Listener() {
                @Override public void onEvent(DebugTraceWireEvent event) {
                    events.add(event);
                    if (events.size() > 300) {
                        events.remove(0);
                    }
                    publish(true);
                }
            });
            return null;
        }, ignored -> { });
    }

    public void stop() {
        subscribed = false;
    }

    public void clear() {
        events.clear();
        publish(true);
    }

    private void publish(boolean connected) {
        state.postValue(new State(connected, List.copyOf(events)));
    }
}
