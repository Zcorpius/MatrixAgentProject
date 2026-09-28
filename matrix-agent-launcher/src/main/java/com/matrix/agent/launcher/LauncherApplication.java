package com.matrix.agent.launcher;

import android.app.Application;

import com.matrix.agent.launcher.data.LauncherHostGateway;
import com.matrix.agent.launcher.data.LauncherExecutorRegistry;
import com.matrix.agent.launcher.overlay.pet.PetSpriteRepository;
import com.matrix.agent.launcher.overlay.pet.PetCharacterPreferences;

/** Process owner for Launcher-only infrastructure.  It never depends on Host implementation code. */
public final class LauncherApplication extends Application {
    private final com.matrix.agent.diagnostics.HandoffDiagnostics diagnostics =
            new com.matrix.agent.diagnostics.HandoffDiagnostics();
    public com.matrix.agent.diagnostics.HandoffDiagnostics diagnostics() { return diagnostics; }
    private LauncherHostGateway hostGateway;
    private LauncherExecutorRegistry executors;
    private PetSpriteRepository petSprites;
    private com.matrix.agent.launcher.overlay.OverlayController overlay;
    private com.matrix.agent.launcher.data.HandoffClient handoff;
    private com.matrix.agent.launcher.data.OverlayPointerClient pointerClient;
    private com.matrix.agent.launcher.data.DraftCommandLane draftLane;
    public com.matrix.agent.launcher.overlay.OverlayController overlay() { return overlay; }
    public com.matrix.agent.launcher.data.DraftCommandLane draftLane() { return draftLane; }

    @Override public void onCreate() {
        super.onCreate();
        executors = new LauncherExecutorRegistry();
        petSprites = new PetSpriteRepository(getAssets(), executors.petDecoding());
        petSprites.warmUp(PetCharacterPreferences.get(this));
        hostGateway = new LauncherHostGateway(this, executors);
        pointerClient = new com.matrix.agent.launcher.data.OverlayPointerClient(hostGateway, executors);
        draftLane = new com.matrix.agent.launcher.data.DraftCommandLane(hostGateway, executors.draftCommands());
        overlay = new com.matrix.agent.launcher.overlay.OverlayController(this, hostGateway,
                new com.matrix.agent.launcher.data.ConversationRepository(hostGateway, draftLane), diagnostics, petSprites, pointerClient);
        handoff = new com.matrix.agent.launcher.data.HandoffClient(hostGateway, executors, overlay);
    }

    public LauncherHostGateway hostGateway() { return hostGateway; }
    public LauncherExecutorRegistry executorRegistry() { return executors; }
    public PetSpriteRepository petSprites() { return petSprites; }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW && petSprites != null) petSprites.trimMemory();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration value) {
        super.onConfigurationChanged(value);
        overlay.configurationChanged();
    }

    @Override public void onTerminate() {
        // Android production process death is abrupt, but this matters for instrumentation and
        // controlled test lifecycles where Application is explicitly terminated.
        handoff.close();
        overlay.close();
        pointerClient.close();
        petSprites.close();
        hostGateway.disconnect();
        executors.shutdown();
        super.onTerminate();
    }
}
