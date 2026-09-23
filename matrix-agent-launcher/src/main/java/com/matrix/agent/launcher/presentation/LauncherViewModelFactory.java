package com.matrix.agent.launcher.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.launcher.data.LauncherHostGateway;
import com.matrix.agent.launcher.data.DraftCommandLane;
import com.matrix.agent.launcher.data.AgentTaskRepository;
import com.matrix.agent.launcher.data.ModelRepository;
import com.matrix.agent.launcher.data.DownloadRepository;
import com.matrix.agent.launcher.data.VoiceRepository;
import com.matrix.agent.launcher.data.ConversationRepository;

/** Explicit dependency injection for Launcher ViewModels; no ViewModel reaches into an Activity. */
public final class LauncherViewModelFactory implements ViewModelProvider.Factory {
    private final LauncherHostGateway gateway;
    private final DraftCommandLane draftLane;

    public LauncherViewModelFactory(LauncherHostGateway gateway, DraftCommandLane draftLane) {
        this.gateway = gateway;
        this.draftLane = draftLane;
    }

    @NonNull @Override @SuppressWarnings("unchecked")
    public <T extends ViewModel> T create(@NonNull Class<T> type) {
        if (type == LauncherViewModel.class) return (T) new LauncherViewModel(gateway);
        if (type == AgentTaskViewModel.class) return (T) new AgentTaskViewModel(new AgentTaskRepository(gateway));
        if (type == ModelViewModel.class) return (T) new ModelViewModel(new ModelRepository(gateway));
        if (type == DownloadViewModel.class) return (T) new DownloadViewModel(new DownloadRepository(gateway));
        if (type == VoiceViewModel.class) return (T) new VoiceViewModel(new VoiceRepository(gateway));
        if (type == ConversationViewModel.class) {
            return (T) new ConversationViewModel(new ConversationRepository(gateway, draftLane));
        }
        throw new IllegalArgumentException("Unsupported Launcher ViewModel: " + type.getName());
    }
}
