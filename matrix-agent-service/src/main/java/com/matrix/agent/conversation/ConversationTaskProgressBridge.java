package com.matrix.agent.conversation;

import com.matrix.agent.task.port.TaskProgressSink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * task → conversation 的进度桥（输入交互增强 I3，§6.2）。
 *
 * <p>实现 task 域的 {@link TaskProgressSink}：Engine 事件只携带 runtimeRequestId，
 * 本桥持有 {@code runtimeRequestId → (conversationId, conversationTaskId)} 的进程内
 * 映射（Coordinator 在受理事务后 bind、终态后 unbind），把事件投影进
 * {@link ConversationRuntimeStageRegistry}。未绑定的 requestId（durable 任务页提交、
 * 非对话入口）静默丢弃——输入栏的运行阶段是会话域能力。</p>
 *
 * <p>capabilityLabel 由装配方注入（Host 侧为 CapabilitySpeechNames::friendlyName），
 * conversation 域不依赖 voice 包。实现自 fail-open：任何异常只记日志，不影响 Engine。</p>
 */
public final class ConversationTaskProgressBridge implements TaskProgressSink {

    private record Binding(String conversationId, String conversationTaskId) { }

    private final ConversationRuntimeStageRegistry registry;
    private final UnaryOperator<String> capabilityLabel;
    private final ConcurrentHashMap<String, Binding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BodyState> bodies = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<AssistantStreamEvent> bodySink = ignored -> { };
    private static final int MAX_BODY_CHARS = 8192;
    private static final long MIN_INTERVAL_NANOS = 100_000_000L;
    private static final class BodyState {
        int turn;
        long sequence, emittedAt;
        boolean closed, turnClosed;
        final StringBuilder text = new StringBuilder();
    }

    public void setAssistantStreamSink(java.util.function.Consumer<AssistantStreamEvent> sink) {
        bodySink = sink == null ? ignored -> { } : sink;
    }

    public ConversationTaskProgressBridge(ConversationRuntimeStageRegistry registry,
            UnaryOperator<String> capabilityLabel) {
        this.registry = registry;
        this.capabilityLabel = capabilityLabel;
    }

    /** Coordinator 在受理事务成功后调用（发布 QUEUED 之前）。 */
    public void bind(String runtimeRequestId, String conversationId,
            String conversationTaskId) {
        bindings.put(runtimeRequestId, new Binding(conversationId, conversationTaskId));
        bodies.put(runtimeRequestId, new BodyState());
    }

    /** Coordinator 在终态清理时调用；幂等。 */
    public void unbind(String runtimeRequestId) {
        Binding binding = bindings.remove(runtimeRequestId);
        BodyState state = bodies.remove(runtimeRequestId);
        if (binding != null && state != null) synchronized (state) {
            state.closed = true;
            emit(binding, runtimeRequestId, state, true);
        }
    }

    @Override public boolean supportsAssistantStream() { return true; }

    @Override public void onAssistantStreamStarted(String request, int turn) {
        BodyState state = bodies.get(request);
        Binding binding = bindings.get(request);
        if (state == null || binding == null) return;
        synchronized (state) {
            if (state.closed || turn <= state.turn) return;
            state.turn = turn;
            state.turnClosed = false;
            state.text.setLength(0);
            state.emittedAt = 0;
            emit(binding, request, state, true);
        }
    }

    @Override public void onAssistantStreamEvent(String request, int turn,
            com.matrix.agent.contract.ModelStreamEvent event) {
        BodyState state = bodies.get(request);
        Binding binding = bindings.get(request);
        if (state == null || binding == null) return;
        synchronized (state) {
            if (state.closed || state.turnClosed || turn != state.turn) return;
            if (event instanceof com.matrix.agent.contract.ModelStreamEvent.BodyDelta delta) {
                int room = MAX_BODY_CHARS - state.text.length();
                if (room <= 0 || delta.text() == null || delta.text().isEmpty()) return;
                int end = Math.min(room, delta.text().length());
                if (Character.isHighSurrogate(delta.text().charAt(end - 1))) end--;
                state.text.append(delta.text(), 0, end);
                long now = System.nanoTime();
                if (state.emittedAt == 0 || now - state.emittedAt >= MIN_INTERVAL_NANOS) {
                    emit(binding, request, state, false);
                    state.emittedAt = now;
                }
            } else if (event instanceof com.matrix.agent.contract.ModelStreamEvent.Completed done) {
                state.turnClosed = true;
                emit(binding, request, state, done.reason() != com.matrix.agent.contract.FinishReason.STOP);
            } else if (event instanceof com.matrix.agent.contract.ModelStreamEvent.Failed) {
                state.turnClosed = true;
                emit(binding, request, state, true);
            }
        }
    }

    private void emit(Binding binding, String request, BodyState state, boolean clear) {
        if (clear) state.text.setLength(0);
        try {
            bodySink.accept(new AssistantStreamEvent(binding.conversationId(), binding.conversationTaskId(),
                    request, state.turn, ++state.sequence, state.text.toString(), clear));
        } catch (RuntimeException ignored) { /* Optional UI delivery cannot affect execution. */ }
    }

    @Override
    public void onModelPlanning(String runtimeRequestId) {
        publish(binding -> registry.publish(binding.conversationId(),
                binding.conversationTaskId(),
                ConversationRuntimeStageRegistry.stagePlanning(), ""), runtimeRequestId);
    }

    @Override
    public void onCapabilityExecuting(String runtimeRequestId, String capabilityName) {
        publish(binding -> registry.publish(binding.conversationId(),
                binding.conversationTaskId(),
                ConversationRuntimeStageRegistry.stageExecuting(),
                capabilityLabel.apply(capabilityName)), runtimeRequestId);
    }

    private void publish(Publisher publisher, String runtimeRequestId) {
        Binding binding = bindings.get(runtimeRequestId);
        if (binding == null) return;
        try {
            publisher.publish(binding);
        } catch (RuntimeException failure) {
            android.util.Log.w("MatrixAgent",
                    "[ConversationProgress] publish failed task=" + binding.conversationTaskId(),
                    failure);
        }
    }

    private interface Publisher {
        void publish(Binding binding);
    }

    /** 测试/诊断：当前绑定数。 */
    public int activeBindings() {
        return bindings.size();
    }

    /** 只保留语义化访问；Map 视图不外泄。 */
    Map<String, Binding> bindingsViewForTest() {
        return bindings;
    }
}
