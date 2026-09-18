package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import android.content.Context;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;

/**
 * 语音装配工厂：把「引擎特定」的准备与装配从 {@code VoiceRuntime} 中分离——
 * Runtime(main,通用生命周期/恢复治理)只依赖本接口,不依赖任何具体引擎。
 *
 * <p>三个定向消费者：release 主源集的 {@code VoskVoiceAssemblyFactory}(Vosk 模型下载+装配)、
 * JVM 测试的假工厂({@code VoiceRuntimeTest} 恢复路径回归)、阶段 3 系统入口运行时。
 *
 * <p>生命周期契约：{@link #prepare}(引擎资源就绪,如模型下载)由 Runtime 的下载线程调用一次;
 * {@link #create}(装配端口+Controller+采音)在 Runtime 持生命周期锁前调用,失败抛异常由 Runtime 回滚;
 * {@link VoiceAssembly#close} 完成 native 生命周期回收(Recognizer 先于 Model close)。
 * 接口面保持最小,不新增投机方法。
 */
public interface VoiceAssemblyFactory {

    /** 装配进度文案(下载/就绪/错误),经 Runtime 的 StatusListener 上浮 UI。 */
    interface ProgressListener {
        void onProgress(String status);
    }

    /**
     * 引擎资源准备(耗时,在 Runtime 下载线程执行):如 Vosk 模型下载与校验。
     * {@code cancelled} 为 true 时立即返回(旧 generation/已销毁);锁竞争类失败
     * 由实现自行有界退避(对齐既有 LockBusy 语义)或抛异常上浮失败状态。
     */
    void prepare(ProgressListener progress, BooleanSupplier cancelled) throws Exception;

    /**
     * 装配(资源已就绪后调用):构造端口集合 + Controller + 采音端口。
     * 前台/已销毁等生命周期判断不在工厂职责内(Runtime 装配后再校验)。
     */
    VoiceAssembly create(AssemblyContext context) throws Exception;

    /** Runtime 提供给装配的共享基础设施(executor 由 Runtime 生命周期统一创建与回收)。 */
    interface AssemblyContext {
        /** 应用上下文(已 getApplicationContext)。 */
        Context appContext();

        /** 语音请求 → AgentRuntime 的进程内适配(已包 repository.execute)。 */
        AgentRunner runner();

        /** Controller 状态执行器(单线程,状态串行)。 */
        Executor stateExecutor();

        /** Agent 执行器。 */
        Executor agentExecutor();

        /** 会话超时 watchdog 调度器。 */
        ScheduledExecutorService timeoutScheduler();
    }
}
