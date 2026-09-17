package com.matrix.agent.host;

import android.app.Application;

public final class MatrixAgentApplication extends Application {
    /** 懒构建(双检 volatile)：系统入口拉起进程时不做全量装配，首个 Host Service 触达才构建。 */
    private volatile AppContainer container;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    public AppContainer getContainer() {
        AppContainer c = container;
        if (c == null) {
            synchronized (this) {
                c = container;
                if (c == null) {
                    c = new AppContainer(this);
                    container = c;
                }
            }
        }
        return c;
    }

    /**
     * 释放 scheduler workers。
     *
     * <p>统一调用 {@link AppContainer#shutdown()},按顺序关闭
     * Repository / schedulerPool / ioPool / auditEventRecorder。
     *
     * <p>注意:Android 真机不保证调用 onTerminate,该方法仅给模拟器 / 集成测试使用。
     * 真机依赖进程级回收(daemon Thread + 进程死即释放)。后续版本接 Car 生命周期后,
     * 通过 CarLifecycleListener 在 Car 析构时显式释放(不依赖 onTerminate)。
     */
    @Override
    public void onTerminate() {
        super.onTerminate();
        if (container != null) {
            container.shutdown();
        }
    }
}
