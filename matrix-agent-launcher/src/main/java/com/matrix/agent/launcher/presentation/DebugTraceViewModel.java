package com.matrix.agent.launcher.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.api.debug.DebugTraceWireEvent;
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
        // 历史由 Fragment 按需加载（buildDebugPanel → loadDebugHistory）
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
