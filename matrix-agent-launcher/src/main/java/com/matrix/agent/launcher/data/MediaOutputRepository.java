package com.matrix.agent.launcher.data;

import com.matrix.agent.api.media.MediaOutputSnapshot;
import com.matrix.agent.client.MediaOutputManager;

import java.util.function.Consumer;

/** Launcher data boundary for the Host-owned system media output controller. */
public final class MediaOutputRepository {
    private final LauncherHostGateway gateway;

    public MediaOutputRepository(LauncherHostGateway gateway) { this.gateway = gateway; }

    public void snapshot(Consumer<LauncherHostGateway.Result<MediaOutputSnapshot>> receiver) {
        gateway.execute(agent -> requireManager(agent.getMediaOutputManager()).getSnapshot(), receiver);
    }

    public void select(int output,
            Consumer<LauncherHostGateway.Result<MediaOutputSnapshot>> receiver) {
        gateway.execute(agent -> requireManager(agent.getMediaOutputManager()).select(output), receiver);
    }

    public void subscribe(Consumer<MediaOutputSnapshot> changed,
            Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver) {
        gateway.execute(agent -> requireManager(agent.getMediaOutputManager()).subscribe(
                snapshot -> gateway.dispatchToMain(() -> changed.accept(snapshot))), receiver);
    }

    public void unsubscribe(AutoCloseable subscription) {
        gateway.executeClientWork(() -> {
            subscription.close();
            return null;
        }, ignored -> { });
    }

    private static MediaOutputManager requireManager(MediaOutputManager manager) {
        if (manager == null) throw new IllegalStateException("Host 尚未提供媒体输出功能");
        return manager;
    }
}
