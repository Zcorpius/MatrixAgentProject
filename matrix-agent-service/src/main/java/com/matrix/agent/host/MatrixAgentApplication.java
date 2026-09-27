package com.matrix.agent.host;
import com.matrix.agent.platform.MatrixExecutorRegistry;
import com.matrix.agent.host.di.*;

import android.app.Application;

import com.matrix.agent.download.DownloadRuntime;
import com.matrix.agent.download.DownloadRuntimeProvider;
import com.matrix.agent.voice.VoiceAssemblyFactory;
import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.system.VoiceRuntimeProvider;
import com.matrix.agent.voice.VoiceEnginePreference;
import com.matrix.agent.voice.vosk.VoskVoiceAssemblyFactory;
import com.matrix.agent.voice.sherpa.SherpaVoiceAssemblyFactory;

public final class MatrixAgentApplication extends Application implements DownloadRuntimeProvider,
        VoiceRuntimeProvider, com.matrix.agent.schedule.ScheduleRuntimeProvider, com.matrix.agent.schedule.execution.ScheduleExecutionRuntime.Provider {
    /** 懒构建(双检 volatile)：系统入口拉起进程时不做全量装配，首个 Host Service 触达才构建。 */
    private volatile AppContainer container;
    private volatile ScheduleGraph schedules;
    private volatile ScheduledExecutionGraph scheduleExecutions;
    private volatile CalendarBindingGateway calendarBindings;
    private final com.matrix.agent.schedule.android.ScheduleWakeReceiver scheduleUserReceiver =
            new com.matrix.agent.schedule.android.ScheduleWakeReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
        android.content.IntentFilter users = new android.content.IntentFilter();
        users.addAction(android.content.Intent.ACTION_USER_FOREGROUND);
        users.addAction(android.content.Intent.ACTION_USER_BACKGROUND);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(scheduleUserReceiver, users, RECEIVER_EXPORTED);
        else registerReceiver(scheduleUserReceiver, users);
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

    @Override public ScheduleGraph scheduleRuntime() {
        ScheduleGraph current = schedules;
        if (current == null) {
            synchronized (this) {
                current = schedules;
                if (current == null) {
                    current = new ScheduleGraph(this);
                    current.setLongExecutionStarter(run -> startForegroundService(new android.content.Intent()
                            .setClassName(this, "com.matrix.agent.schedule.android.ScheduleExecutionService")
                            .putExtra("runId", run)));
                    schedules = current;
                }
            }
        }
        return current;
    }

    public synchronized CalendarBindingGateway calendarBindings() {
        if (calendarBindings == null) calendarBindings = new CalendarBindingGateway(scheduleRuntime(), this::getContainer);
        return calendarBindings;
    }
    @Override public synchronized com.matrix.agent.schedule.execution.ScheduleExecutionRuntime scheduleExecutionRuntime() {
        if (scheduleExecutions == null) scheduleExecutions = new ScheduledExecutionGraph(scheduleRuntime(), this::getContainer, calendarBindings());
        return scheduleExecutions;
    }

    @Override
    public DownloadRuntime downloadRuntime() {
        return getContainer();
    }

    @Override
    public VoiceRuntime createVoiceRuntime(Application application) {
        AppContainer c = getContainer();
        MatrixExecutorRegistry registry = c.getExecutorRegistry();
        // ASR 引擎按偏好装配（语音页可切换；下次 VoiceRuntime 构建生效）。
        // Sherpa 需要 ASR 模型就绪（prepare fail-closed），缺模型时装配失败由
        // VoiceRuntime 语义收敛——不静默回退 Vosk，保证用户对"当前引擎"的预期。
        VoiceAssemblyFactory factory = new VoiceEnginePreference(this).isSherpaSelected()
                ? new SherpaVoiceAssemblyFactory(application, c.getModelDownloadDao(),
                        registry.voiceCaptureThreadFactory(), c.getHttpClient().download(),
                        c.getHttpClient().provider())
                : new VoskVoiceAssemblyFactory(application, c.getModelDownloadDao(),
                        registry.voiceCaptureThreadFactory(), c.getHttpClient().download(),
                        c.getHttpClient().provider());
        return new VoiceRuntime(application, c.getAgentRuntimeRepository(),
                factory,
                registry.voiceDownloadExecutor(), registry.voiceStateExecutor(),
                registry.voiceAgentExecutor(), registry.voiceLifecycleExecutor(),
                registry.voiceTimeoutScheduler());
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
        if (scheduleExecutions != null) scheduleExecutions.close();
        if (schedules != null) schedules.close();
        if (container != null) {
            container.shutdown();
        }
        // Shared persistence is process-owned, not tied to the lifetime of any Host Service.
        PersistenceRuntimeGraph.closeIfInitialized();
    }
}
