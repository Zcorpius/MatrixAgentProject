package com.matrix.agent.task.conversation;

import com.matrix.agent.contract.ConversationSeedContext;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.InputSource;
import com.matrix.agent.intent.IntentClassifier;
import com.matrix.agent.intent.MemoryIntentDetector;

import java.util.Objects;

/**
 * task 域导出的纯准备端口（设计文档 §4.3，M-2 裁决）。
 *
 * <p>把一次对话提交固化为 {@link PreparedTask}：分类快照（readOnlyHint /
 * memorySaveAllowed——与调度期使用的值同源，保证恢复对账与实际执行不漂移）+ 种子 +
 * 构造 AgentRequest 所需的全部输入。本类<b>不构造 AgentRequest 本体、不读写任何
 * conversation 表、不接触执行器</b>——request 由 keyed lane 出队方经
 * {@code TaskRequestFactory.newPreparedRequestBuilder()} 构造（deadline 从出队起算），
 * 事务与排队预约由 conversation 域 Coordinator 持有。</p>
 */
public final class ConversationTaskSubmitter {

    /** 提交期不可变分类快照；恢复对账据此区分 FAILED / EXECUTION_UNKNOWN。 */
    public record ClassificationSnapshot(boolean readOnlyHint, boolean memorySaveAllowed) { }

    /**
     * 出队即可执行的完整输入。runtimeRequestId 为提交期持久化的稳定 UUID，
     * 出队构造 request 时注入复用（审计/轨迹 id 不漂移）。
     */
    public record PreparedTask(
            String runtimeRequestId,
            String conversationTaskId,
            String conversationId,
            ClassificationSnapshot classification,
            ConversationSeedContext seed,
            String text,
            Actor actor,
            String agentSessionId,
            String arbitrationKey,
            InputSource inputSource,
            String languageTag,
            float asrConfidence,
            boolean confidenceAvailable) { }

    /** conversation 域提交入口的输入（不含 Host 内部派生字段）。 */
    public record SubmitInput(
            String conversationTaskId,
            String runtimeRequestId,
            String conversationId,
            String text,
            Actor actor,
            String agentSessionId,
            String arbitrationKey,
            InputSource inputSource,
            String languageTag,
            Float asrConfidence,
            boolean confidenceAvailable) { }

    private final IntentClassifier classifier;
    private final MemoryIntentDetector memoryIntentDetector;
    private final ConversationContextAssembler assembler;

    public ConversationTaskSubmitter(IntentClassifier classifier,
            MemoryIntentDetector memoryIntentDetector, ConversationContextAssembler assembler) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.memoryIntentDetector = Objects.requireNonNull(memoryIntentDetector, "memoryIntentDetector");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    public PreparedTask prepare(SubmitInput input) {
        Objects.requireNonNull(input, "input");
        requireNonBlank(input.conversationTaskId(), "conversationTaskId");
        requireNonBlank(input.runtimeRequestId(), "runtimeRequestId");
        requireNonBlank(input.conversationId(), "conversationId");
        requireNonBlank(input.text(), "text");
        Objects.requireNonNull(input.actor(), "actor");
        requireNonBlank(input.agentSessionId(), "agentSessionId");
        requireNonBlank(input.arbitrationKey(), "arbitrationKey");
        Objects.requireNonNull(input.inputSource(), "inputSource");
        String languageTag = (input.languageTag() == null || input.languageTag().isBlank())
                ? "zh-CN" : input.languageTag();
        float confidence = input.asrConfidence() == null ? 1.0f : input.asrConfidence();

        ClassificationSnapshot snapshot = new ClassificationSnapshot(
                classifier.isReadOnly(input.text()),
                memoryIntentDetector.isExplicitMemorySave(input.text()));
        return new PreparedTask(
                input.runtimeRequestId(),
                input.conversationTaskId(),
                input.conversationId(),
                snapshot,
                new ConversationSeedContext(java.util.List.of()),
                input.text(),
                input.actor(),
                input.agentSessionId(),
                input.arbitrationKey(),
                input.inputSource(),
                languageTag,
                confidence,
                input.confidenceAvailable());
    }

    /**
     * 由 conversation 的 keyed lane 在真正出队时调用。分类与身份仍严格使用提交期快照；
     * 唯有历史 seed 晚绑定，从而包含同一会话此前刚落库的终态，而不让排队时间污染预算。
     */
    public PreparedTask assembleAtExecution(PreparedTask prepared) {
        Objects.requireNonNull(prepared, "prepared");
        return new PreparedTask(prepared.runtimeRequestId(), prepared.conversationTaskId(),
                prepared.conversationId(), prepared.classification(),
                assembler.assemble(prepared.conversationId(), prepared.text()), prepared.text(),
                prepared.actor(), prepared.agentSessionId(), prepared.arbitrationKey(),
                prepared.inputSource(), prepared.languageTag(), prepared.asrConfidence(),
                prepared.confidenceAvailable());
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
    }
}
