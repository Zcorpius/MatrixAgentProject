package com.matrix.agent.model;

import android.util.Log;

import com.matrix.agent.task.DemoModelGateway;
import com.matrix.agent.task.ModelGateway;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.FallbackIntentClassifier;
import com.matrix.agent.task.identity.IntentClassifier;
import com.matrix.agent.task.identity.KeywordIntentClassifier;
import com.matrix.agent.task.identity.LlmIntentClassifier;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.model.LlmModelGateway;
import com.matrix.agent.model.ModelApiClient;
import com.matrix.agent.model.ModelConfig;
import com.matrix.agent.model.ModelProviderPreset;
import com.matrix.agent.model.SecureModelConfigStore;
import com.matrix.agent.model.ApiProtocol;
import com.matrix.agent.model.OnDeviceModelGateway;
import com.matrix.agent.ondevice.MnnLoadOptions;
import com.matrix.agent.ondevice.OnDeviceLlm;
import com.matrix.agent.ondevice.OnDeviceLlmFactory;

import android.content.Context;

import java.io.File;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

public final class ModelGatewayRepository {
    private static final String TAG = "MatrixAgent";
    private static final Pattern SAFE_ON_DEVICE_MODEL_NAME =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,119}");
    private final SecureModelConfigStore configStore;
    private final ModelApiClient modelClient;
    private final CapabilityRegistry registry;
    private final MemoryStore memoryStore;
    /** 可选 Memory 召回——null 时 LlmPlanner 退化为旧版行为。 */
    private final MemoryRecaller memoryRecaller;
    /** 端侧推理：appContext（模型目录 filesDir/models/mnn）+ 工厂；null 表示未装配端侧。 */
    private final Context appContext;
    private final OnDeviceLlmFactory onDeviceLlmFactory;
    /** Serialises every persistent model-configuration transition, regardless of backend. */
    private final ReentrantLock modelMutationLock = new ReentrantLock(true);
    /** Serialises native model validation/activation with deletion of its backing directory. */
    private final ReentrantLock onDeviceMutationLock = new ReentrantLock(true);

    @FunctionalInterface
    public interface OnDeviceMutation<T> { T run() throws Exception; }

    @FunctionalInterface
    public interface ModelMutation<T> { T run() throws Exception; }

    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry) {
        this(configStore, modelClient, registry, null);
    }

    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry, MemoryStore memoryStore) {
        this(configStore, modelClient, registry, memoryStore, null);
    }

    /** 注入 Memory 召回器,createModelGateway 内部传给 LlmPlanner。 */
    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry, MemoryStore memoryStore,
            MemoryRecaller memoryRecaller) {
        this(configStore, modelClient, registry, memoryStore, memoryRecaller, null, null);
    }

    /** 端侧推理装配：注入 appContext（模型目录 filesDir/models/mnn）+ OnDeviceLlmFactory。 */
    public ModelGatewayRepository(SecureModelConfigStore configStore,
            ModelApiClient modelClient, CapabilityRegistry registry, MemoryStore memoryStore,
            MemoryRecaller memoryRecaller, Context appContext, OnDeviceLlmFactory onDeviceLlmFactory) {
        this.configStore = configStore;
        this.modelClient = modelClient;
        this.registry = registry;
        this.memoryStore = memoryStore;
        this.memoryRecaller = memoryRecaller;
        this.appContext = appContext;
        this.onDeviceLlmFactory = onDeviceLlmFactory;
    }

    /**
     * Probes a configured provider without persisting or activating it.  The caller owns the
     * optional token: cancelling it interrupts a remote transport immediately and asks a native
     * probe to stop before its temporary session is released.
     */
    public String testConnection(ModelConfig config, CancellationToken cancellationToken)
            throws Exception {
        config.validate();
        if (config.protocol == ApiProtocol.ON_DEVICE) {
            return withOnDeviceMutationLock(() -> testOnDevice(config, cancellationToken));
        }
        Log.i(TAG, "[ModelRepo] testConnection provider=" + config.displayName
                + " model=" + config.model + " protocol=" + config.protocol);
        String reply = modelClient.complete(config, "你是连接测试助手。", "只回复 OK",
                cancellationToken, Long.MAX_VALUE);
        Log.i(TAG, "[ModelRepo] testConnection OK replyChars=" + reply.length());
        return reply;
    }

    /** Backwards-compatible convenience overload for local callers that do not need cancellation. */
    public String testConnection(ModelConfig config) throws Exception {
        return testConnection(config, null);
    }

    /** 端侧测试：create(load 模型) → generate("OK") → close，验证模型可用。 */
    private String testOnDevice(ModelConfig config, CancellationToken cancellationToken)
            throws Exception {
        if (appContext == null || onDeviceLlmFactory == null) {
            throw new IllegalStateException("端侧推理未装配（缺 appContext/OnDeviceLlmFactory）");
        }
        if (!isArm64()) {
            throw new IllegalStateException("端侧推理仅支持 arm64-v8a（当前设备 ABI: "
                    + android.os.Build.SUPPORTED_ABIS[0] + "）");
        }
        File modelDir = resolveOnDeviceModelDir(config);
        Log.i(TAG, "[ModelRepo] testOnDevice modelDir=" + modelDir);
        OnDeviceLlm llm = onDeviceLlmFactory.create(modelDir.getAbsolutePath(),
                MnnLoadOptions.cpuDefaults());
        Runnable abort = llm::cancel;
        if (cancellationToken != null) cancellationToken.registerAbortHook(abort);
        try {
            com.matrix.agent.ondevice.GenerationResult r = llm.generate(
                    "[{\"role\":\"user\",\"content\":\"只回复 OK\"}]", null, 64,
                    () -> cancellationToken != null && cancellationToken.isCancelled());
            if (r.finishReason == com.matrix.agent.ondevice.OnDeviceFinishReason.FAILED) {
                throw new RuntimeException("端侧推理失败: " + r.nativeError);
            }
            if (r.finishReason == com.matrix.agent.ondevice.OnDeviceFinishReason.CANCELLED) {
                throw new RuntimeException("端侧推理被取消");
            }
            String text = r.text == null ? "" : r.text;
            if (text.trim().isEmpty()) {
                throw new RuntimeException("端侧模型返回空答复");
            }
            Log.i(TAG, "[ModelRepo] testOnDevice OK genTokens=" + r.generatedTokens
                    + " prefillMs=" + (r.prefillUs / 1000));
            return "端侧加载成功 · genTokens=" + r.generatedTokens
                    + " prefillMs=" + (r.prefillUs / 1000) + " · " + text;
        } finally {
            if (cancellationToken != null) cancellationToken.removeAbortHook(abort);
            llm.close();
        }
    }

    public void save(ModelConfig config) throws Exception {
        config.validate();
        Log.i(TAG, "[ModelRepo] save provider=" + config.displayName
                + " model=" + config.model + " mode=" + config.plannerMode
                + " (key length=" + (config.apiKey == null ? 0 : config.apiKey.length()) + ")");
        configStore.save(config);
    }

    public ModelConfig load() {
        ModelConfig loaded = configStore.load();
        Log.d(TAG, "[ModelRepo] load -> " + (loaded == null ? "null" :
                "provider=" + loaded.displayName + " model=" + loaded.model + " mode=" + loaded.plannerMode));
        return loaded;
    }
    public List<ModelProviderPreset> getProviderPresets() { return ModelProviderPreset.all(); }

    public ModelGateway createModelGateway(ModelConfig config) {
        if (config.protocol == ApiProtocol.ON_DEVICE) {
            return createOnDeviceGateway(config);
        }
        Log.i(TAG, "[ModelRepo] create LlmModelGateway provider=" + config.displayName);
        LlmModelGateway gateway = new LlmModelGateway(modelClient, config, registry, memoryStore);
        // 把 Memory 召回器透传给 LlmPlanner(结构化 JSON 兼容路径)
        if (memoryRecaller != null) gateway.setMemoryRecaller(memoryRecaller);
        return gateway;
    }

    /**
     * Makes state-changing operations on an on-device model atomic with its backing files.
     * Callers must keep the critical section limited to validation/activation or deletion; native
     * inference itself is protected by {@link OnDeviceModelGateway}'s lease protocol instead.
     */
    public <T> T withOnDeviceMutationLock(OnDeviceMutation<T> mutation) throws Exception {
        if (mutation == null) throw new IllegalArgumentException("mutation required");
        onDeviceMutationLock.lock();
        try {
            return mutation.run();
        } finally {
            onDeviceMutationLock.unlock();
        }
    }

    /**
     * Serialises configuration save/activation and startup recovery across cloud and on-device
     * backends. File deletion takes this lock before the narrower on-device lock, establishing
     * one lock order for all model lifecycle mutations.
     */
    public <T> T withModelMutationLock(ModelMutation<T> mutation) throws Exception {
        if (mutation == null) throw new IllegalArgumentException("mutation required");
        modelMutationLock.lock();
        try {
            return mutation.run();
        } finally {
            modelMutationLock.unlock();
        }
    }

    /** 端侧：model 目录 = filesDir/models/mnn/<config.model>。加载耗时，调用方须在 worker 线程。 */
    private ModelGateway createOnDeviceGateway(ModelConfig config) {
        if (appContext == null || onDeviceLlmFactory == null) {
            throw new IllegalStateException("端侧推理未装配（缺 appContext/OnDeviceLlmFactory）");
        }
        if (!isArm64()) {
            throw new IllegalStateException("端侧推理仅支持 arm64-v8a（当前设备 ABI: "
                    + android.os.Build.SUPPORTED_ABIS[0] + "）");
        }
        File modelDir = resolveOnDeviceModelDir(config);
        Log.i(TAG, "[ModelRepo] create OnDeviceModelGateway modelDir=" + modelDir);
        try {
            OnDeviceLlm llm = onDeviceLlmFactory.create(modelDir.getAbsolutePath(),
                    MnnLoadOptions.cpuDefaults());
            return new OnDeviceModelGateway(llm, config.displayName, 1536);
        } catch (Exception e) {
            throw new RuntimeException("端侧模型加载失败: " + e.getMessage(), e);
        }
    }

    public ModelGateway createDemoGateway() {
        Log.i(TAG, "[ModelRepo] create DemoModelGateway (offline)");
        return new DemoModelGateway();
    }

    /**
     * 用 {@link ModelConfig} 构建 {@link IntentClassifier}。
     *
     * <p>返回 {@link FallbackIntentClassifier}(LlmIntentClassifier 主路径,失败 / 低置信度退 Keyword)。
     * Host 的 {@code ModelServiceStub} 在同一模型变更事务内把这个分类器与 gateway 一起发布，
     * 让意图分类与新 Provider 同步切换，避免“已应用”但分类仍使用旧配置的伪装状态。
     */
    public IntentClassifier buildIntentClassifier(ModelConfig config) {
        if (config.protocol == ApiProtocol.ON_DEVICE) {
            Log.i(TAG, "[ModelRepo] build KeywordIntentClassifier (on-device offline)");
            return KeywordIntentClassifier.INSTANCE;
        }
        Log.i(TAG, "[ModelRepo] build FallbackIntentClassifier provider=" + config.displayName);
        LlmIntentClassifier llm = new LlmIntentClassifier(modelClient, config);
        return new FallbackIntentClassifier(llm, KeywordIntentClassifier.INSTANCE);
    }

    /**
     * Demo 路径回退 Keyword——零成本 fallback,确保切回离线时
     * LLM 不再被调用。
     */
    public IntentClassifier buildKeywordClassifier() {
        Log.i(TAG, "[ModelRepo] build KeywordIntentClassifier (offline demo)");
        return KeywordIntentClassifier.INSTANCE;
    }

    /** 端侧 .so 仅 arm64-v8a，非 arm64 设备前置拒绝（避免 UnsatisfiedLinkError）。 */
    private static boolean isArm64() {
        for (String abi : android.os.Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    /**
     * Resolves an on-device model only below the Host-private MNN root. The model identifier is
     * a catalog id, not a user path: separators, dot-only names and traversal fragments are all
     * rejected before any native code sees a path.
     */
    private File resolveOnDeviceModelDir(ModelConfig config) {
        if (config == null || config.model == null
                || !SAFE_ON_DEVICE_MODEL_NAME.matcher(config.model).matches()) {
            throw new IllegalArgumentException("端侧模型名必须是受信任的市场 ID");
        }
        File root = new File(appContext.getFilesDir(), "models/mnn");
        File candidate = new File(root, config.model);
        try {
            String rootPath = root.getCanonicalPath();
            String candidatePath = candidate.getCanonicalPath();
            if (!candidatePath.startsWith(rootPath + File.separator)) {
                throw new IllegalArgumentException("端侧模型目录越出私有 MNN 根目录");
            }
        } catch (java.io.IOException error) {
            throw new IllegalStateException("无法解析端侧模型私有目录", error);
        }
        return candidate;
    }

    public String displayName(ModelConfig config) {
        return config.displayName + " / " + config.model + " / " + config.plannerMode.displayName;
    }
}
