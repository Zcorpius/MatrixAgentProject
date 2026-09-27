package com.matrix.agent.launcher;

import android.app.Application;

import com.matrix.agent.launcher.data.LauncherHostGateway;
import com.matrix.agent.launcher.data.LauncherExecutorRegistry;

/** Process owner for Launcher-only infrastructure.  It never depends on Host implementation code. */
public final class LauncherApplication extends Application {
    private final com.matrix.agent.diagnostics.HandoffDiagnostics diagnostics =
            new com.matrix.agent.diagnostics.HandoffDiagnostics();
    public com.matrix.agent.diagnostics.HandoffDiagnostics diagnostics() { return diagnostics; }
    private LauncherHostGateway hostGateway;
    private LauncherExecutorRegistry executors;
    private com.matrix.agent.launcher.overlay.OverlayController overlay;
    private com.matrix.agent.launcher.data.HandoffClient handoff;
    private com.matrix.agent.launcher.data.DraftCommandLane draftLane;
    public com.matrix.agent.launcher.overlay.OverlayController overlay() { return overlay; }
    public com.matrix.agent.launcher.data.DraftCommandLane draftLane() { return draftLane; }

    @Override public void onCreate() {
        super.onCreate();
        executors = new LauncherExecutorRegistry();
        hostGateway = new LauncherHostGateway(this, executors);
        draftLane = new com.matrix.agent.launcher.data.DraftCommandLane(hostGateway, executors.draftCommands());
        overlay = new com.matrix.agent.launcher.overlay.OverlayController(this, hostGateway,
                new com.matrix.agent.launcher.data.ConversationRepository(hostGateway, draftLane), diagnostics);
        handoff = new com.matrix.agent.launcher.data.HandoffClient(hostGateway, executors, overlay);
    }

    public LauncherHostGateway hostGateway() { return hostGateway; }
    public LauncherExecutorRegistry executorRegistry() { return executors; }

    @Override public void onConfigurationChanged(android.content.res.Configuration value) {
        super.onConfigurationChanged(value);
        overlay.configurationChanged();
    }

    @Override public void onTerminate() {
        // Android production process death is abrupt, but this matters for instrumentation and
        // controlled test lifecycles where Application is explicitly terminated.
        handoff.close();
        overlay.close();
        hostGateway.disconnect();
        executors.shutdown();
        super.onTerminate();
    }
}
