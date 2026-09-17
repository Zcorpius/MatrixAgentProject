package com.matrix.agent.ondevice;

/** MNN 模型加载选项。对应 MNNLlmSession.create 的运行时参数（通过 nativeSetConfig 在 load 前设置）。 */
public final class MnnLoadOptions {
    private static final int MIN_THREAD_COUNT = 1;
    private static final int MAX_THREAD_COUNT = 32;

    /** 后端类型："cpu" / "opencl" / "vulkan"。CPU-only 首发：仅 "cpu" 被编入。 */
    public final String backendType;
    /** 推理线程数。 */
    public final int threadNum;
    /** 精度："low" / "normal" / "high"。默认 low 提速。 */
    public final String precision;
    /** 内存模式："low" / "normal" / "high"。CPU 用 low。 */
    public final String memory;
    /** 缓存目录；null 则用 modelDir。 */
    public final String tmpPath;
    /** 是否启用 thinking（Qwen3 等推理模型默认开，消耗大量 token；验证/车机场景通常关闭）。 */
    public final boolean enableThinking;

    public MnnLoadOptions(String backendType, int threadNum, String precision, String memory, String tmpPath) {
        this(backendType, threadNum, precision, memory, tmpPath, false);
    }

    public MnnLoadOptions(String backendType, int threadNum, String precision, String memory,
            String tmpPath, boolean enableThinking) {
        this.backendType = backendType;
        this.threadNum = threadNum;
        this.precision = precision;
        this.memory = memory;
        this.tmpPath = tmpPath;
        this.enableThinking = enableThinking;
    }

    /** CPU 后端默认值（与 Operit MNNLlmSession.create 默认一致；thinking 关闭）。 */
    public static MnnLoadOptions cpuDefaults() {
        return new MnnLoadOptions("cpu", 4, "low", "low", null, false);
    }

    /**
     * Validates values before they are serialized into MNN's native JSON configuration.
     * This module is built CPU-only, so accepting a GPU backend would expose a configuration
     * that cannot be fulfilled by the packaged native library.
     */
    public void validate() {
        if (!"cpu".equals(backendType)) {
            throw new IllegalArgumentException("only the packaged CPU backend is supported");
        }
        if (threadNum < MIN_THREAD_COUNT || threadNum > MAX_THREAD_COUNT) {
            throw new IllegalArgumentException("threadNum must be between " + MIN_THREAD_COUNT
                    + " and " + MAX_THREAD_COUNT);
        }
        if (!isLevel(precision)) {
            throw new IllegalArgumentException("invalid precision");
        }
        if (!isLevel(memory)) {
            throw new IllegalArgumentException("invalid memory mode");
        }
    }

    private static boolean isLevel(String value) {
        return "low".equals(value) || "normal".equals(value) || "high".equals(value);
    }
}
