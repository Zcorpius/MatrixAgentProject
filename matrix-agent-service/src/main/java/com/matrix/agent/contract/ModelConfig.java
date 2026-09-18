package com.matrix.agent.contract;

public final class ModelConfig {
    private static final String SAFE_ON_DEVICE_MODEL = "[A-Za-z0-9][A-Za-z0-9._-]{0,119}";
    private static final String SAFE_PROVIDER_ID = "[a-z0-9_]{1,40}";
    private static final String SAFE_REMOTE_MODEL = "(?=.{1,120}$)[A-Za-z0-9._:-]+(/[A-Za-z0-9._:-]+)?";
    private static final int MAX_ENDPOINT_CHARS = 512;
    private static final int MAX_SECRET_CHARS = 8 * 1024;
    public final String providerId;
    public final String displayName;
    public final ApiProtocol protocol;
    public final String endpoint;
    public final String model;
    public final String apiKey;
    public final boolean apiKeyRequired;
    public final PlannerMode plannerMode;

    public ModelConfig(String providerId, String displayName, ApiProtocol protocol,
            String endpoint, String model, String apiKey, boolean apiKeyRequired) {
        this(providerId, displayName, protocol, endpoint, model, apiKey, apiKeyRequired,
                PlannerMode.STRUCTURED_JSON_COMPATIBILITY);
    }

    public ModelConfig(String providerId, String displayName, ApiProtocol protocol,
            String endpoint, String model, String apiKey, boolean apiKeyRequired,
            PlannerMode plannerMode) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.protocol = protocol;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.apiKeyRequired = apiKeyRequired;
        this.plannerMode = plannerMode == null
                ? PlannerMode.STRUCTURED_JSON_COMPATIBILITY : plannerMode;
    }

    public void validate() {
        if (providerId == null || !providerId.matches(SAFE_PROVIDER_ID)) {
            throw new IllegalArgumentException("Provider id 非法");
        }
        if (displayName == null || displayName.trim().isEmpty() || displayName.length() > 160) {
            throw new IllegalArgumentException("Provider 展示名非法");
        }
        if (protocol == null) throw new IllegalArgumentException("Protocol 不能为空");
        if (apiKey.length() > MAX_SECRET_CHARS) {
            throw new IllegalArgumentException("API Key 过长");
        }
        if (protocol == ApiProtocol.ON_DEVICE) {
            // 端侧不走 HTTP。model 会被拼为 Host 私有 models/mnn/<model> 目录，必须是
            // 单个安全目录名，不能把任何调用方传入的路径带进 native loader。
            if (!isSafeOnDeviceModelId(model)) {
                throw new IllegalArgumentException("端侧模型名必须是安全的单级目录名");
            }
            return;
        }
        validateEndpoint(endpoint, protocol);
        if (model == null || !model.matches(SAFE_REMOTE_MODEL)) {
            throw new IllegalArgumentException("Model 标识符非法");
        }
        if (apiKeyRequired && apiKey.trim().isEmpty()) throw new IllegalArgumentException("该厂商需要 API Key");
        if (plannerMode == PlannerMode.NATIVE_TOOL_CALLING
                && protocol != ApiProtocol.OPENAI_CHAT
                && protocol != ApiProtocol.ANTHROPIC_MESSAGES
                && protocol != ApiProtocol.GEMINI_GENERATE_CONTENT) {
            throw new IllegalArgumentException(
                    "原生 Tool Calling 当前仅支持 OpenAI-Compatible / Anthropic Messages / Gemini 协议");
        }
    }

    static boolean isSafeOnDeviceModelId(String model) {
        return model != null && model.matches(SAFE_ON_DEVICE_MODEL);
    }

    /**
     * Validates the persisted HTTP target. Gemini is the sole supported URI-template protocol;
     * it may use exactly one {@code {model}} path placeholder which is later replaced by a
     * separately validated model identifier.
     */
    public static void validateEndpoint(String value, ApiProtocol protocol) {
        if (value == null || value.trim().isEmpty() || value.length() > MAX_ENDPOINT_CHARS) {
            throw new IllegalArgumentException("Endpoint 非法");
        }
        String parseable = value;
        if (protocol == ApiProtocol.GEMINI_GENERATE_CONTENT && value.contains("{model}")) {
            if (value.indexOf("{model}") != value.lastIndexOf("{model}")
                    || value.replace("{model}", "").contains("{")
                    || value.replace("{model}", "").contains("}")) {
                throw new IllegalArgumentException("Endpoint 模板非法");
            }
            parseable = value.replace("{model}", "model");
        } else if (value.contains("{") || value.contains("}")) {
            throw new IllegalArgumentException("Endpoint 模板非法");
        }
        try {
            java.net.URI uri = new java.net.URI(parseable);
            if (!uri.isAbsolute() || uri.getHost() == null
                    || !("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Endpoint 非法");
            }
        } catch (java.net.URISyntaxException invalid) {
            throw new IllegalArgumentException("Endpoint 非法", invalid);
        }
    }
}
