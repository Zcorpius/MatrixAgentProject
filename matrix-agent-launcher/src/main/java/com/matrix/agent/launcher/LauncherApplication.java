package com.matrix.agent.launcher;

import android.app.Application;

import com.matrix.agent.launcher.data.LauncherHostGateway;
import com.matrix.agent.launcher.data.LauncherExecutorRegistry;

/** Process owner for Launcher-only infrastructure.  It never depends on Host implementation code. */
public final class LauncherApplication extends Application {
    private LauncherHostGateway hostGateway;
    private LauncherExecutorRegistry executors;

    @Override public void onCreate() {
        super.onCreate();
        executors = new LauncherExecutorRegistry();
        hostGateway = new LauncherHostGateway(this, executors);
    }

    public LauncherHostGateway hostGateway() { return hostGateway; }

    @Override public void onTerminate() {
        // Android production process death is abrupt, but this matters for instrumentation and
        // controlled test lifecycles where Application is explicitly terminated.
        hostGateway.disconnect();
        executors.shutdown();
        super.onTerminate();
    }
}
