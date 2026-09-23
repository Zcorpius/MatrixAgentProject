package com.matrix.agent.model;

import com.matrix.agent.api.model.ModelRuntimeStatus;
import com.matrix.agent.contract.ApiProtocol;
import com.matrix.agent.contract.ModelConfig;

import java.util.Objects;

/**
 * 提交受理时写入 conversation_task_link 的受限模型快照（输入交互增强 I5 §8.2）。
 *
 * <p>“下一次提交将使用的当前 Host 模型”在受理瞬间的不可变事实：此后胶囊/当前配置
 * 无论如何切换，本任务“用的是哪个配置代际”不再漂移。全部字段为非秘密投影
 * （无 API key / 完整 endpoint）；Phase 2 不经 SDK 暴露——未来历史执行环境查询走
 * 独立的脱敏 TaskDetails 契约，而非混入消息正文。</p>
 *
 * @param providerId provider 标识（glm / anthropic / on_device…）
 * @param modelId 模型 ID（端侧为模型目录名）
 * @param backend {@link ModelRuntimeStatus#BACKEND_NONE/CLOUD/ON_DEVICE} 冻结值
 * @param configGeneration SecureModelConfigStore 的单调配置代际（0 = 从未配置）
 * @param configFingerprint 非秘密字段 SHA-256 指纹
 */
public record ModelExecutionSnapshot(String providerId, String modelId, int backend,
        int configGeneration, String configFingerprint) {

    public ModelExecutionSnapshot {
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(configFingerprint, "configFingerprint");
    }

    /** 当前配置 → 快照（generation/fingerprint 来自注入的供应方；模型未配置返回 null）。 */
    public static ModelExecutionSnapshot of(ModelConfig config, int generation,
            String fingerprint) {
        if (config == null) return null;
        return new ModelExecutionSnapshot(config.providerId, config.model,
                backendOf(config), generation, fingerprint);
    }

    public static int backendOf(ModelConfig config) {
        return config.protocol == ApiProtocol.ON_DEVICE
                ? ModelRuntimeStatus.BACKEND_ON_DEVICE
                : ModelRuntimeStatus.BACKEND_CLOUD;
    }

    /** 指纹的端点类别输入：只区分云端 / 局域网（localhost / 127.* / 192.168.* / 10.*），不暴露主机名。 */
    public static String endpointCategory(ApiProtocol protocol, String endpoint) {
        if (protocol == ApiProtocol.ON_DEVICE) return "ondevice";
        String host = hostOf(endpoint);
        if (host == null) return "cloud";
        if (host.equals("localhost") || host.startsWith("127.")
                || host.startsWith("192.168.") || host.startsWith("10.")
                || host.startsWith("169.254.")) {
            return "lan";
        }
        return "cloud";
    }

    private static String hostOf(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return null;
        try {
            java.net.URI uri = java.net.URI.create(endpoint.trim());
            return uri.getHost();
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
