package com.matrix.agent.launcher.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.launcher.data.LauncherHostGateway;

/** Activity-scoped connection state; navigation Activity only renders this state. */
public final class LauncherViewModel extends ViewModel {
    private final LauncherHostGateway gateway;
    private final LauncherHostGateway.ConnectionLease connectionLease;

    public LauncherViewModel(LauncherHostGateway gateway) {
        this.gateway = gateway;
        connectionLease = gateway.acquireConnection();
    }

    public LiveData<Integer> connectionState() { return gateway.connectionState(); }
    public LauncherHostGateway gateway() { return gateway; }

    @Override protected void onCleared() { connectionLease.close(); }
}
