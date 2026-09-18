package com.matrix.agent.ondevice.mnn;

import com.matrix.agent.ondevice.MnnLoadOptions;
import com.matrix.agent.ondevice.OnDeviceLlm;
import com.matrix.agent.ondevice.OnDeviceLlmFactory;

import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 默认 {@link OnDeviceLlmFactory}：创建 {@link MnnOnDeviceLlm}，并从 {@code llm_config.json}
 * 读取 {@code max_all_tokens}（默认 2048）用于 token 预算公式。
 *
 * <p>创建含 native 模型加载（耗时），调用方必须在 worker 线程调用。
 * {@code :matrix-agent-service} 可直接用此工厂，或注入自定义实现。
 */
public final class MnnOnDeviceLlmFactory implements OnDeviceLlmFactory {
    private static final int DEFAULT_MAX_ALL_TOKENS = 2048;
    private static final int MIN_MAX_ALL_TOKENS = 256;
    private static final int MAX_MAX_ALL_TOKENS = 131072;
    private final ScheduledExecutorService deferredReleaseScheduler;

    /** Standalone/test factory; production Host supplies its bounded scheduler explicitly. */
    public MnnOnDeviceLlmFactory() {
        this(null);
    }

    public MnnOnDeviceLlmFactory(ScheduledExecutorService deferredReleaseScheduler) {
        this.deferredReleaseScheduler = deferredReleaseScheduler;
    }

    @Override
    public OnDeviceLlm create(String modelDir, MnnLoadOptions options) throws Exception {
        MnnLoadOptions opts = options != null ? options : MnnLoadOptions.cpuDefaults();
        MNNLlmSession session = MNNLlmSession.create(modelDir, opts, deferredReleaseScheduler);
        if (session == null) {
            throw new IllegalStateException("MNN 模型加载失败: " + modelDir
                    + "（检查 llm_config.json/llm.mnn 及子文件完整性）");
        }
        try {
            // P2-15: MNNLlmSession.create 内已在 load 前 setConfig enable_thinking；此处再调一次为保险
            // （MNN 是否在 load 时快照 jinja 未文档化，pre+post 双设覆盖两种行为）。
            session.setThinkingMode(opts.enableThinking);
            return new MnnOnDeviceLlm(session, readMaxAllTokens(modelDir));
        } catch (RuntimeException e) {
            session.release(); // P2-23: 后续步骤失败时释放 native session，不泄漏
            throw e;
        }
    }

    private static int readMaxAllTokens(String modelDir) {
        try {
            File configFile = new File(modelDir, "llm_config.json");
            JSONObject cfg = new JSONObject(new String(Files.readAllBytes(configFile.toPath())));
            int tokens = cfg.optInt("max_all_tokens", DEFAULT_MAX_ALL_TOKENS);
            return tokens >= MIN_MAX_ALL_TOKENS && tokens <= MAX_MAX_ALL_TOKENS
                    ? tokens : DEFAULT_MAX_ALL_TOKENS;
        } catch (Exception e) {
            return DEFAULT_MAX_ALL_TOKENS; // 读不到用默认
        }
    }
}
