package com.matrix.agent.identity;

import com.matrix.agent.contract.ConversationSeedContext;
import com.matrix.agent.vehicle.VehicleState;
import java.util.Locale;
import java.util.UUID;

public final class AgentRequest {
    private final String requestId;
    private final String sessionId;
    /**
     * 调度仲裁键——同车不同座位的请求共享同一仲裁队列,
     * 让 TaskScheduler 的主驾优先抢占在物理可触达。与 {@link #sessionId} 区分:
     * <ul>
     *   <li>{@code arbitrationKey}——TaskScheduler.runningTasks / SessionLockManager
     *       (Scheduler 内部) 用的 key,主驾和副驾共享(同车仲裁);</li>
     *   <li>{@code sessionId}——SessionManager.getOrCreate / SteerMailbox.drain 用的 key,
     *       按乘员隔离(对话上下文 + Steer 不串扰)。</li>
     * </ul>
     * 默认 = sessionId(向后兼容),生产路径由 Repository 显式注入。
     */
    private final String arbitrationKey;
    private final String text;
    private final Actor actor;
    private final VehicleZone occupantZone;
    private final ExplicitIntentConstraints explicitIntent;
    private final int displayId;
    private final int audioZoneId;
    private final InputSource inputSource;
    private final String languageTag;
    private final float asrConfidence;
    /** ASR 置信度是否可用。false 表示未知(语义由 FinalTranscript 归零保证)。 */
    private final boolean confidenceAvailable;
    private final long createdAtMillis;
    private final long deadlineAtMillis;
    private final CancellationToken cancellationToken;
    private final boolean readOnlyHint;
    /**
     * 当前车辆状态(PolicyEngine 用此判定 requiredVehicleStates 前置约束)。
     * 默认 {@link VehicleState#satisfyAllPredicates()} mock state,保证现有测试不退绿。
     */
    private final VehicleState currentVehicleState;
    /**
     * data epoch——Repository 在 {@code clearUserData()} 时自增的全局版本号。
     *
     * <p>{@code epoch} 在 {@code execute()} 入口捕获,注入 AgentRequest。Provider 调用
     * {@code MemoryStore.putPreferenceChecked(userId, key, value, epoch)} 时由 MemoryStore
     * 校验:若 {@code epoch < currentEpoch} 则拒绝写入 (任务在 clearUserData 之前启动,
     * Provider 完成时数据已被清,不能把陈旧偏好写回)。
     *
     * <p>默认 {@code 0L}——不参与校验,等价于旧版行为 (best-effort clear)。
     * 由 Repository 在 build 阶段显式注入,289 现有测试默认 0 不破坏。
     */
    private final long epoch;
    /**
     * 用户是否显式要求长期记忆保存(由 MemoryIntentDetector 在
     * Repository.execute 入口判定)。
     *
     * <p>true:用户原文含"记住/保存/以后默认/不要忘记"等关键词,memory.semantic.save
     * capability handler 放行;false:未命中,handler 直接 POLICY_REJECTED——
     * 杜绝模型在纯查询请求里自由调 save 污染长期记忆。
     *
     * <p>默认 false(保守);与 {@link #readOnlyHint} 同 builder 模式,
     * 289 现有测试默认 false 不破坏。
     */
    private final boolean memorySaveAllowed;
    /**
     * 跨任务对话历史种子（可空）。由 task 域装配器在调度出队时注入；Engine 在当前
     * user 消息前写入模型 conversation。见 contract.ConversationSeedContext 内容契约。
     */
    private final ConversationSeedContext conversationSeed;
    /** 预生成 requestId 覆盖（可空）：对话域把提交期持久化的稳定 UUID 复用为执行期 id。 */
    private final String requestIdOverride;

    public AgentRequest(String text, Actor actor) {
        this(builder(text, actor)
                .sessionId("demo-" + actor.name().toLowerCase(Locale.ROOT))
                .occupantZone(actor == Actor.DRIVER ? VehicleZone.DRIVER : VehicleZone.PASSENGER)
                .explicitIntent(ExplicitIntentConstraints.extractFrom(text)));
    }

    private AgentRequest(Builder builder) {
        // 对话域注入的稳定 id（提交期已持久化到 conversation_task_link.runtime_request_id）
        // 必须最先赋值：sessionId 的空回退以它兜底（与旧版“先 requestId 后 sessionId”次序一致）。
        requestIdOverride = builder.requestIdOverride == null
                ? null : requireValidRequestId(builder.requestIdOverride);
        this.requestId = requestIdOverride != null
                ? requestIdOverride : UUID.randomUUID().toString();
        text = builder.text == null ? "" : builder.text.trim();
        actor = builder.actor;
        sessionId = builder.sessionId == null || builder.sessionId.trim().isEmpty()
                ? requestId : builder.sessionId.trim();
        // arbitrationKey 默认 = sessionId,向后兼容现有测试
        arbitrationKey = builder.arbitrationKey == null || builder.arbitrationKey.trim().isEmpty()
                ? sessionId : builder.arbitrationKey.trim();
        occupantZone = builder.occupantZone;
        explicitIntent = builder.explicitIntent == null
                ? ExplicitIntentConstraints.empty() : builder.explicitIntent;
        displayId = builder.displayId;
        audioZoneId = builder.audioZoneId;
        inputSource = builder.inputSource;
        languageTag = builder.languageTag;
        asrConfidence = builder.asrConfidence;
        confidenceAvailable = builder.confidenceAvailable;
        createdAtMillis = System.currentTimeMillis();
        deadlineAtMillis = createdAtMillis + builder.timeoutMillis;
        cancellationToken = builder.cancellationToken;
        readOnlyHint = builder.readOnlyHint;
        currentVehicleState = builder.currentVehicleState == null
                ? VehicleState.satisfyAllPredicates() : builder.currentVehicleState;
        epoch = builder.epoch;
        memorySaveAllowed = builder.memorySaveAllowed;
        conversationSeed = builder.conversationSeed;
    }

    private static String requireValidRequestId(String value) {
        // 与 host 侧 AgentRequestValidator.isLowerUuid 同语义，但 identity 叶子不得依赖 host，
        // 故在此内联等价校验：UUID 规范形且小写。
        try {
            if (!java.util.UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("requestIdOverride 必须是小写 UUID");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("requestIdOverride 必须是小写 UUID");
        }
        return value;
    }

    public String getRequestId() { return requestId; }
    public String getSessionId() { return sessionId; }
    /** 调度仲裁键(默认 = sessionId)。TaskScheduler 内部用此 key。 */
    public String getArbitrationKey() { return arbitrationKey; }
    public String getText() { return text; }
    public Actor getActor() { return actor; }
    public VehicleZone getOccupantZone() { return occupantZone; }
    public ExplicitIntentConstraints getExplicitIntent() { return explicitIntent; }
    public int getDisplayId() { return displayId; }
    public int getAudioZoneId() { return audioZoneId; }
    public InputSource getInputSource() { return inputSource; }
    public String getLanguageTag() { return languageTag; }
    public float getAsrConfidence() { return asrConfidence; }
    /** ASR 置信度是否可用。文本入口默认 true(历史占位),语音入口由 FinalTranscript 透传。 */
    public boolean isConfidenceAvailable() { return confidenceAvailable; }
    public long getCreatedAtMillis() { return createdAtMillis; }
    public long getDeadlineAtMillis() { return deadlineAtMillis; }
    public CancellationToken getCancellationToken() { return cancellationToken; }
    public boolean isCancelled() { return cancellationToken.isCancelled(); }
    public boolean isTimedOut() { return System.currentTimeMillis() >= deadlineAtMillis; }
    public long remainingMillis() { return Math.max(0L, deadlineAtMillis - System.currentTimeMillis()); }
    /**
     * TaskScheduler 抢占判断的关键 hint。
     *
     * <p>true 表示这是查询/问答类请求(读操作),允许被主驾同 hint 的请求抢占;
     * false 表示车控写操作(温度调节 / 导航 / 偏好保存等),不强制中断。
     *
     * <p>由 Builder.readOnlyHint 设置,默认 false(保守——不知是否只读就不抢占)。
     */
    public boolean isReadOnlyHint() { return readOnlyHint; }
    /** 当前车辆状态(PolicyEngine 判定 requiredVehicleStates 用)。 */
    public VehicleState getCurrentVehicleState() { return currentVehicleState; }
    /**
     * data epoch——Repository clearUserData 时自增的版本号。
     * Provider 通过此值与 MemoryStore.currentEpoch() 对比,拒绝陈旧写入。
     */
    public long getEpoch() { return epoch; }
    /**
     * 用户是否显式要求长期记忆保存。
     * memory.semantic.save handler 在 false 时直接 POLICY_REJECTED。
     */
    public boolean isMemorySaveAllowed() { return memorySaveAllowed; }
    /** 跨任务对话历史种子；null 表示普通（非对话）任务。 */
    public ConversationSeedContext getConversationSeed() { return conversationSeed; }

    public static Builder builder(String text, Actor actor) {
        return new Builder(text, actor);
    }

    public static final class Builder {
        private final String text;
        private final Actor actor;
        private String sessionId;
        private String arbitrationKey;
        private VehicleZone occupantZone;
        private ExplicitIntentConstraints explicitIntent;
        private int displayId;
        private int audioZoneId;
        private InputSource inputSource = InputSource.TOUCH;
        private String languageTag = "zh-CN";
        private float asrConfidence = 1.0f;
        private boolean confidenceAvailable = true;
        private long timeoutMillis = 60_000L;
        private CancellationToken cancellationToken = new CancellationToken();
        private boolean readOnlyHint = false;
        private VehicleState currentVehicleState;
        private long epoch = 0L;
        private boolean memorySaveAllowed = false;
        private ConversationSeedContext conversationSeed;
        private String requestIdOverride;

        private Builder(String text, Actor actor) {
            if (actor == null) throw new IllegalArgumentException("actor 不能为空");
            this.text = text;
            this.actor = actor;
            occupantZone = actor == Actor.DRIVER ? VehicleZone.DRIVER : VehicleZone.PASSENGER;
            explicitIntent = ExplicitIntentConstraints.extractFrom(text);
        }

        public Builder sessionId(String value) { sessionId = value; return this; }
        /** 调度仲裁键(默认 = sessionId)。同车不同座位共享,跨 session 隔离。 */
        public Builder arbitrationKey(String value) { arbitrationKey = value; return this; }
        public Builder occupantZone(VehicleZone value) { occupantZone = value; return this; }
        public Builder explicitIntent(ExplicitIntentConstraints value) { explicitIntent = value; return this; }
        public Builder displayId(int value) { displayId = value; return this; }
        public Builder audioZoneId(int value) { audioZoneId = value; return this; }
        public Builder inputSource(InputSource value) { inputSource = value; return this; }
        public Builder languageTag(String value) { languageTag = value; return this; }
        public Builder asrConfidence(float value) { asrConfidence = value; return this; }
        /** 注入 ASR 置信度可用性。默认 true 向后兼容文本入口。 */
        public Builder confidenceAvailable(boolean value) { confidenceAvailable = value; return this; }
        public Builder timeoutMillis(long value) { timeoutMillis = value; return this; }
        public Builder cancellationToken(CancellationToken value) { cancellationToken = value; return this; }
        /** 标记查询/问答类请求,允许被主驾同 hint 请求抢占。 */
        public Builder readOnlyHint(boolean value) { readOnlyHint = value; return this; }
        /** 注入当前车辆状态(默认 satisfyAllPredicates mock state)。 */
        public Builder vehicleState(VehicleState value) { currentVehicleState = value; return this; }
        /**
         * 注入 data epoch——由 Repository 在 execute 入口捕获并传入,
         * Provider 通过 {@link AgentRequest#getEpoch()} 与 MemoryStore.currentEpoch() 对比。
         */
        public Builder epoch(long value) { epoch = value; return this; }
        /**
         * 注入"用户是否显式要求长期记忆"标志——由 Repository 在 execute
         * 入口经 {@link MemoryIntentDetector} 判定后传入。memory.semantic.save handler
         * 在 false 时直接 POLICY_REJECTED。默认 false(保守)。
         */
        public Builder memorySaveAllowed(boolean value) { memorySaveAllowed = value; return this; }
        /** 注入跨任务对话历史种子（task 装配器专用；普通任务不设）。 */
        public Builder conversationSeed(ConversationSeedContext value) { conversationSeed = value; return this; }
        /** 注入提交期已持久化的稳定 requestId（对话域专用；小写 UUID，构造时校验）。 */
        public Builder requestId(String value) { requestIdOverride = value; return this; }
        public AgentRequest build() {
            if (occupantZone == null) throw new IllegalArgumentException("occupantZone 不能为空");
            if (inputSource == null) throw new IllegalArgumentException("inputSource 不能为空");
            if (displayId < 0) throw new IllegalArgumentException("displayId 不能小于 0");
            if (audioZoneId < 0) throw new IllegalArgumentException("audioZoneId 不能小于 0");
            if (languageTag == null || languageTag.trim().isEmpty()) {
                throw new IllegalArgumentException("languageTag 不能为空");
            }
            if (!Float.isFinite(asrConfidence) || asrConfidence < 0f || asrConfidence > 1f) {
                throw new IllegalArgumentException("asrConfidence 必须在 0 到 1 之间");
            }
            if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis 必须大于 0");
            if (cancellationToken == null) throw new IllegalArgumentException("cancellationToken 不能为空");
            return new AgentRequest(this);
        }
    }
}
