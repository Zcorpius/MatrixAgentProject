package com.matrix.agent.ondevice;

/**
 * {@link OnDeviceLlm} 工厂。:app 的 {@code ModelGatewayRepository} 用它创建端侧 gateway。
 *
 * <p>创建含模型加载（耗时，几秒~几十秒），调用方必须在 worker 线程调用，
 * 完成后再热切换进 AgentRuntimeRepository（不能阻塞启动/UI）。
 */
public interface OnDeviceLlmFactory {

    /**
     * @param modelDir 模型目录，必须含 {@code llm_config.json} + {@code llm.mnn}（+ weight/embedding 等子文件）
     * @param options  加载选项；null 用 {@link MnnLoadOptions#cpuDefaults()}
     */
    OnDeviceLlm create(String modelDir, MnnLoadOptions options) throws Exception;
}
