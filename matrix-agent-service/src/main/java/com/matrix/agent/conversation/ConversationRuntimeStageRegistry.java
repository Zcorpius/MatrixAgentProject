package com.matrix.agent.conversation;

import com.matrix.agent.api.conversation.ConversationRuntimeStage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 会话运行阶段的内存注册表（输入交互增强 I3，§6.2）。
 *
 * <p>只存内存、不进 conversation_message：进程死亡后的恢复走既有终态对账，不伪造
 * 过去的阶段。连续同阶段（同任务同标签）合并——满足“最短 200ms 合并高频进度”的
 * v1 语义：真实转换立即推送，无变化不推送。终态清理顺序由调用方（Coordinator）
 * 保证：先 message upsert，再 clear——输入栏不会短暂显示“正在执行”而消息已“已完成”。</p>
 *
 * <p>线程模型：Engine worker（发布）、Binder 线程（订阅快照）、lane 线程（清理）
 * 并发触达；方法级 synchronized 足够（事件频率每任务个位数）。</p>
 */
public final class ConversationRuntimeStageRegistry {

    /** 领域事件；Host 边界（Stub）投影为 SDK DTO。 */
    public record StageEvent(String conversationId, String conversationTaskId,
            long generation, int stage, String safeLabel, long occurredAtMs, boolean snapshot) { }

    /** 单一监听口：Stub 装配时注入 Binder 分发；cleared 不出 wire（终态消息替代阶段）。 */
    public interface Listener {
        default void onRuntimeStage(StageEvent event) { }
        default void onStageCleared(String conversationId, String conversationTaskId) { }
    }

    private static final class Active {
        final String conversationTaskId;
        long generation;
        int stage;
        String safeLabel;
        long occurredAtMs;

        Active(String conversationTaskId, long generation, int stage, String safeLabel,
                long occurredAtMs) {
            this.conversationTaskId = conversationTaskId;
            this.generation = generation;
            this.stage = stage;
            this.safeLabel = safeLabel;
            this.occurredAtMs = occurredAtMs;
        }
    }

    private final LongSupplier clock;
    /** 一会话可同时有执行中任务和若干 queued 任务；UI 只投影其中优先级最高的一项。 */
    private final Map<String, Map<String, Active>> byConversation = new HashMap<>();
    private volatile Listener listener = new Listener() { };

    public ConversationRuntimeStageRegistry() {
        this(System::currentTimeMillis);
    }

    /** 测试注入时钟。 */
    public ConversationRuntimeStageRegistry(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void setListener(Listener value) {
        this.listener = value == null ? new Listener() { } : value;
    }

    /**
     * 发布一次阶段转换。同任务同阶段同标签 → 合并（返回 false，不通知监听）；
     * 同任务同阶段同标签 → 合并；阶段更新后按“执行中优先、否则最近 QUEUED”投影。
     * 新 QUEUED 不得覆盖正在 EXECUTING 的宿主任务。
     */
    public boolean publish(String conversationId, String conversationTaskId,
            int stage, String safeLabel) {
        StageEvent event = null;
        Listener target;
        synchronized (this) {
            Map<String, Active> tasks = byConversation.computeIfAbsent(conversationId,
                    ignored -> new HashMap<>());
            Active before = visible(tasks);
            Active active = tasks.get(conversationTaskId);
            boolean sameTask = active != null;
            String normalizedLabel = safeLabel == null ? "" : safeLabel;
            if (sameTask && active.stage == stage
                    && Objects.equals(active.safeLabel, normalizedLabel)) {
                return false;
            }
            long now = clock.getAsLong();
            long generation = sameTask && active != null ? active.generation + 1L : 1L;
            active = new Active(conversationTaskId, generation, stage, normalizedLabel, now);
            tasks.put(conversationTaskId, active);
            Active after = visible(tasks);
            if (before != after || (after != null && after.conversationTaskId.equals(conversationTaskId))) {
                event = toEvent(conversationId, after, false);
            }
            target = listener;
        }
        // Binder fan-out must never run under the registry monitor. A remote callback can be
        // slow or re-entrant; generation makes any cross-thread delivery reordering harmless.
        if (event != null) target.onRuntimeStage(event);
        return true;
    }

    /** 订阅受理时的当前快照（snapshot=true）；无活跃任务返回 null。 */
    public synchronized StageEvent snapshotOf(String conversationId) {
        Map<String, Active> tasks = byConversation.get(conversationId);
        Active active = visible(tasks);
        if (active == null) return null;
        return toEvent(conversationId, active, true);
    }

    /** 终态清理：仅当活跃记录仍指向该任务时移除（迟到清理不误删后继任务）。 */
    public void clear(String conversationId, String conversationTaskId) {
        Listener target;
        StageEvent replacement = null;
        boolean cleared = false;
        synchronized (this) {
            Map<String, Active> tasks = byConversation.get(conversationId);
            if (tasks == null || tasks.remove(conversationTaskId) == null) {
                return;
            }
            if (tasks.isEmpty()) {
                byConversation.remove(conversationId);
                cleared = true;
            } else {
                replacement = toEvent(conversationId, visible(tasks), false);
            }
            target = listener;
        }
        if (replacement != null) {
            target.onRuntimeStage(replacement);
        } else if (cleared) {
            target.onStageCleared(conversationId, conversationTaskId);
        }
    }

    /** clearUserData / 域关闭路径。 */
    public synchronized void clearAll() {
        byConversation.clear();
    }

    /** stage wire 常量直通（DTO 单一来源）。 */
    public static int stageQueued() { return ConversationRuntimeStage.STAGE_QUEUED; }

    public static int stagePlanning() { return ConversationRuntimeStage.STAGE_PLANNING; }

    public static int stageExecuting() { return ConversationRuntimeStage.STAGE_EXECUTING; }

    /** 执行中（PLANNING/EXECUTING）优先；没有执行中任务时才选最近入队的 QUEUED。 */
    private static Active visible(Map<String, Active> tasks) {
        if (tasks == null || tasks.isEmpty()) return null;
        Active selected = null;
        for (Active candidate : tasks.values()) {
            boolean candidateExecuting = candidate.stage != ConversationRuntimeStage.STAGE_QUEUED;
            boolean selectedExecuting = selected != null
                    && selected.stage != ConversationRuntimeStage.STAGE_QUEUED;
            if (selected == null || (candidateExecuting && !selectedExecuting)
                    || (candidateExecuting == selectedExecuting
                    && candidate.occurredAtMs > selected.occurredAtMs)) {
                selected = candidate;
            }
        }
        return selected;
    }

    private static StageEvent toEvent(String conversationId, Active active, boolean snapshot) {
        return new StageEvent(conversationId, active.conversationTaskId, active.generation,
                active.stage, active.safeLabel, active.occurredAtMs, snapshot);
    }
}
