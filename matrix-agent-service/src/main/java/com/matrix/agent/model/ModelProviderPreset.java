package com.matrix.agent.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ModelProviderPreset {
    public final String id;
    public final String name;
    public final ApiProtocol protocol;
    public final String endpoint;
    public final String model;
    public final boolean apiKeyRequired;

    private ModelProviderPreset(String id, String name, ApiProtocol protocol,
            String endpoint, String model, boolean apiKeyRequired) {
        this.id = id;
        this.name = name;
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKeyRequired = apiKeyRequired;
    }

    public ModelConfig toConfig(String apiKey) {
        return new ModelConfig(id, name, protocol, endpoint, model, apiKey, apiKeyRequired);
    }

    public static List<ModelProviderPreset> all() {
        return Collections.unmodifiableList(Arrays.asList(
                new ModelProviderPreset("glm", "智谱 GLM", ApiProtocol.OPENAI_CHAT,
                        "https://open.bigmodel.cn/api/paas/v4/chat/completions", "glm-5.2", true),
                new ModelProviderPreset("deepseek", "DeepSeek", ApiProtocol.OPENAI_CHAT,
                        "https://api.deepseek.com/chat/completions", "deepseek-v4-flash", true),
                new ModelProviderPreset("qwen", "阿里通义千问", ApiProtocol.OPENAI_CHAT,
                        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3.7-plus", true),
                new ModelProviderPreset("kimi", "Moonshot Kimi", ApiProtocol.OPENAI_CHAT,
                        "https://api.moonshot.cn/v1/chat/completions", "kimi-k2.5", true),
                new ModelProviderPreset("doubao", "火山方舟/豆包", ApiProtocol.OPENAI_CHAT,
                        "https://ark.cn-beijing.volces.com/api/v3/chat/completions", "请填写 Endpoint ID 或模型 ID", true),
                new ModelProviderPreset("anthropic", "Anthropic Claude", ApiProtocol.ANTHROPIC_MESSAGES,
                        "https://api.anthropic.com/v1/messages", "claude-sonnet-4-5", true),
                new ModelProviderPreset("gemini", "Google Gemini", ApiProtocol.GEMINI_GENERATE_CONTENT,
                        "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent", "gemini-3.5-flash", true),
                new ModelProviderPreset("ollama", "本地 Ollama", ApiProtocol.OLLAMA_CHAT,
                        "http://10.0.2.2:11434/api/chat", "qwen3:8b", false),
                new ModelProviderPreset("lmstudio", "本地 LM Studio", ApiProtocol.OPENAI_CHAT,
                        "http://10.0.2.2:1234/v1/chat/completions", "local-model", false),
                new ModelProviderPreset("vllm", "本地 vLLM", ApiProtocol.OPENAI_CHAT,
                        "http://10.0.2.2:8000/v1/chat/completions", "local-model", false),
                new ModelProviderPreset("ondevice", "端侧 MNN（本地大模型）", ApiProtocol.ON_DEVICE,
                        "", "", false),
                new ModelProviderPreset("custom", "自定义 OpenAI-Compatible", ApiProtocol.OPENAI_CHAT,
                        "https://example.com/v1/chat/completions", "model-name", false)
        ));
    }
}
