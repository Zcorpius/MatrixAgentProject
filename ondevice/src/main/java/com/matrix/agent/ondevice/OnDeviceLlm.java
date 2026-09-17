package com.matrix.agent.ondevice;

/**
 * 端侧 LLM 推理接口（:ondevice 对外的纯接口，<b>不依赖 {@code core.agent}</b>）。
 *
 * <p>实现方（{@code MnnOnDeviceLlm}）负责加载本地 MNN 模型，把 structured messages+tools JSON
 * 套进模型 chat template 并推理。{@link #generate} 是<b>同步阻塞</b>调用——调用方
 * （{@code OnDeviceModelGateway}）负责串行化（MNN session 不能并发生成）与线程调度。
 *
 * <p>生命周期：{@link #close()} 释放 native session（GB 级资源）；<b>必须等在途 generate 返回后才能调</b>，
 * 否则 use-after-free。由 {@code :app} 的 GatewayLifecycleManager 在 lease 引用归零后调用。
 */
public interface OnDeviceLlm {

    /**
     * 同步推理。把全部输出缓冲成 String 返回（structured 模式后处理，非流式触发）。
     *
     * @param messagesJson  OpenAI 风格 messages 数组（含 system，已去重）
     * @param toolsJson     OpenAI 风格 tools 数组；无工具传 null
     * @param maxNewTokens  最大生成 token 数
     * @param cancelChecker 取消检查（读 volatile state.cancelled，原子）——返回 true 时停止生成
     */
    GenerationResult generate(String messagesJson, String toolsJson, int maxNewTokens,
            java.util.function.BooleanSupplier cancelChecker);

    /**
     * 用模型自带 tokenizer 计数（含 chat template + tools 开销）。
     * 返回 0 视为模板/配置错误（不当"可激进裁剪"），由调用方报错处理。
     */
    int countTokens(String messagesJson, String toolsJson);

    /** 读模型 {@code max_all_tokens}（来自 llm_config.json，默认 2048），用于 token 预算公式。 */
    int maxAllTokens();

    /** 模型是否已加载就绪。 */
    boolean isReady();

    /**
     * 清 KV cache（不释放模型权重）；多轮间复用前调用。
     *
     * <p><b>不可与 {@link #generate} 并发</b>——MNN session 非线程安全，调用方必须串行化。
     * OnDeviceModelGateway 通过 sessionLock 保证；直接调用 OnDeviceLlm 时须自行串行。
     */
    void reset();

    /**
     * 中断当前在跑的 generate（供 gateway abort/retire）。非阻塞——设置 native cancel flag，
     * 让在途 generate 尽快返回 CANCELLED；不影响后续调用。幂等。
     */
    void cancel();

    /** 释放 native session。等在途 generate 返回后调。幂等。 */
    void close();
}
