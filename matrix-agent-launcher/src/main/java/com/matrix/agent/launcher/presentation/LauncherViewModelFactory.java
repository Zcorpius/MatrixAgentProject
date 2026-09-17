package com.matrix.agent.launcher.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.launcher.data.LauncherHostGateway;
import com.matrix.agent.launcher.data.AgentTaskRepository;
import com.matrix.agent.launcher.data.ModelRepository;
import com.matrix.agent.launcher.data.DownloadRepository;

/** Explicit dependency injection for Launcher ViewModels; no ViewModel reaches into an Activity. */
public final class LauncherViewModelFactory implements ViewModelProvider.Factory {
    private final LauncherHostGateway gateway;
    public LauncherViewModelFactory(LauncherHostGateway gateway) { this.gateway = gateway; }

    @NonNull @Override @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> type) {
        if (type == LauncherViewModel.class) return (T) new LauncherViewModel(gateway);
        if (type == AgentTaskViewModel.class) return (T) new AgentTaskViewModel(new AgentTaskRepository(gateway));
        if (type == ModelViewModel.class) return (T) new ModelViewModel(new ModelRepository(gateway));
        if (type == DownloadViewModel.class) return (T) new DownloadViewModel(new DownloadRepository(gateway));
        throw new IllegalArgumentException("Unsupported Launcher ViewModel: " + type.getName());
    }
}
