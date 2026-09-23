package com.matrix.agent.task;

import com.matrix.agent.platform.AuditDigest;
import com.matrix.agent.task.compress.*;

import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.task.port.TaskAuditSink;
import com.matrix.agent.task.port.TaskMemoryWriter;
import com.matrix.agent.task.port.TaskProgressSink;
import com.matrix.agent.task.prompt.PromptContextAssembler;
import com.matrix.agent.task.token.Tokenizer;

/**
 * AgentEngine 的一次性装配配置。
 *
 * <p>配置在 Engine 交给调度器前创建，避免构造后再逐项 setter 注入造成的半初始化状态。
 * 未指定的扩展能力有明确的安全默认值，供旧构造器和 JVM 测试使用。</p>
 */
public final class AgentEngineConfiguration {
    private final TaskAuditSink auditSink;
    private final TaskMemoryWriter memoryWriter;
    private final AuditEventRecorder auditEventRecorder;
    private final AuditDigest auditDigest;
    private final Tokenizer tokenizer;
    private final PromptContextAssembler promptContextAssembler;
    private final ConversationCompressor conversationCompressor;
    private final TaskProgressSink taskProgressSink;

    private AgentEngineConfiguration(Builder builder) {
        auditSink = builder.auditSink == null ? TaskAuditSink.NOOP : builder.auditSink;
        memoryWriter = builder.memoryWriter == null ? TaskMemoryWriter.NOOP : builder.memoryWriter;
        auditEventRecorder = builder.auditEventRecorder == null
                ? AuditEventRecorder.NOOP : builder.auditEventRecorder;
        auditDigest = builder.auditDigest;
        tokenizer = builder.tokenizer;
        promptContextAssembler = builder.promptContextAssembler == null
                ? new PromptContextAssembler(null, null) : builder.promptContextAssembler;
        conversationCompressor = builder.conversationCompressor;
        taskProgressSink = builder.taskProgressSink == null
                ? TaskProgressSink.NOOP : builder.taskProgressSink;
    }

    public static AgentEngineConfiguration defaults() { return new Builder().build(); }
    public TaskAuditSink auditSink() { return auditSink; }
    public TaskMemoryWriter memoryWriter() { return memoryWriter; }
    public AuditEventRecorder auditEventRecorder() { return auditEventRecorder; }
    public AuditDigest auditDigest() { return auditDigest; }
    public Tokenizer tokenizer() { return tokenizer; }
    public PromptContextAssembler promptContextAssembler() { return promptContextAssembler; }
    public ConversationCompressor conversationCompressor() { return conversationCompressor; }
    public TaskProgressSink taskProgressSink() { return taskProgressSink; }

    public static final class Builder {
        private TaskAuditSink auditSink;
        private TaskMemoryWriter memoryWriter;
        private AuditEventRecorder auditEventRecorder;
        private AuditDigest auditDigest;
        private Tokenizer tokenizer;
        private PromptContextAssembler promptContextAssembler;
        private ConversationCompressor conversationCompressor;
        private TaskProgressSink taskProgressSink;

        public Builder auditSink(TaskAuditSink value) { auditSink = value; return this; }
        public Builder taskMemoryWriter(TaskMemoryWriter value) { memoryWriter = value; return this; }
        public Builder auditEventRecorder(AuditEventRecorder value) { auditEventRecorder = value; return this; }
        public Builder auditDigest(AuditDigest value) { auditDigest = value; return this; }
        public Builder tokenizer(Tokenizer value) { tokenizer = value; return this; }
        public Builder promptContextAssembler(PromptContextAssembler value) {
            promptContextAssembler = value;
            return this;
        }
        public Builder conversationCompressor(ConversationCompressor value) {
            conversationCompressor = value;
            return this;
        }
        public Builder taskProgressSink(TaskProgressSink value) { taskProgressSink = value; return this; }
        public AgentEngineConfiguration build() { return new AgentEngineConfiguration(this); }
    }
}
