package com.matrix.agent.api.common;

/**
 * Matrix Service 发现键。客户端只使用这些常量，不写裸字符串。
 *
 * <p>Published literals are frozen ABI. Platform SELinux service_contexts must use these exact
 * values; future aliases may be added but existing values must never change.
 */
public final class MatrixServiceConstants {

    /** 唯一注册到 ServiceManager 的全局 Agent Manager Binder 名。 */
    public static final String MATRIX_AGENT_SERVICE = "matrix_agent_service";

    /** Explicit Android binding contract for clients that cannot use ServiceManager directly. */
    public static final String HOST_PACKAGE = "com.matrix.agent";
    public static final String HOST_MANAGER_SERVICE_CLASS =
            "com.matrix.agent.host.MatrixAgentManagerService";

    /** 根 Binder 的服务发现键 → IMatrixAgentManager。 */
    public static final String MANAGER_SERVICE = "matrix.service.MANAGER";
    public static final String MODEL_SERVICE = "matrix.service.MODEL";
    public static final String VOICE_SERVICE = "matrix.service.VOICE";
    public static final String DOWNLOAD_SERVICE = "matrix.service.DOWNLOAD";
    /** 对话域发现键 → IConversationService；特性位见 {@link #FEATURE_CONVERSATION_DOMAIN}。 */
    public static final String CONVERSATION_SERVICE = "matrix.service.CONVERSATION";
    /** 调试轨迹发现键 → IDebugTraceService（评估 v1.0 §4.3；BuildConfig 门控，UI 关闭时空壳）。 */
    public static final String DEBUG_TRACE_SERVICE = "matrix.service.DEBUG_TRACE";
    /**
     * 受控附件 staging 发现键 → IConversationAttachmentService（输入交互增强 I6，v8）。
     * 刻意不复用对话域键：附件是窄能力，老 Host 不通告时客户端按位独立降级。
     */
    public static final String HANDOFF_SERVICE = "matrix.service.HANDOFF";
    public static final int FEATURE_HANDOFF_DOMAIN = 1 << 7;

    public static final String ATTACHMENT_SERVICE = "matrix.service.ATTACHMENT";

    /** Host notification → Launcher download page deep-link contract. */
    public static final String ACTION_OPEN_DOWNLOADS = "com.matrix.agent.action.OPEN_DOWNLOADS";
    public static final String LAUNCHER_PACKAGE = "com.matrix.agent.launcher";

    /** Frozen feature bits advertised by AgentServiceInfo; unknown bits must be ignored. */
    public static final int FEATURE_DURABLE_TASKS = 1;
    public static final int FEATURE_MODEL_DOMAIN = 1 << 1;
    public static final int FEATURE_DOWNLOAD_DOMAIN = 1 << 2;
    public static final int FEATURE_VOICE_DOMAIN = 1 << 3;
    public static final int FEATURE_PERSISTENCE_GATE = 1 << 4;
    /**
     * 对话域特性位。刻意不并入 {@link #ALL_STAGE_B_FEATURES}：后者是冻结的阶段 B 必备位集合，
     * 对话域是增量能力——客户端按位独立探测，未通告时视为该 Host 版本不支持对话。
     */
    public static final int FEATURE_CONVERSATION_DOMAIN = 1 << 5;
    /**
     * 附件 staging 特性位（输入交互增强 I6，v8）：SQLCipher 可用时才通告；
     * 未通告的 Host 上客户端隐藏 `+` 入口而不是调用后吃异常。
     */
    public static final int FEATURE_ATTACHMENT_DOMAIN = 1 << 6;

    public static final int ALL_STAGE_B_FEATURES = FEATURE_DURABLE_TASKS
            | FEATURE_MODEL_DOMAIN | FEATURE_DOWNLOAD_DOMAIN | FEATURE_VOICE_DOMAIN
            | FEATURE_PERSISTENCE_GATE;

    private MatrixServiceConstants() {
    }
}
