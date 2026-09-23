package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.conversation.ConversationRuntimeStage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行阶段注册表（I3 §6.2）：连续同阶段合并、同任务 generation 单调、新任务接管
 * 重置、清理只命中本任务、订阅快照带 snapshot 标记。
 */
public final class ConversationRuntimeStageRegistryTest {

    private final AtomicLong clock = new AtomicLong(1_000L);
    private final ConversationRuntimeStageRegistry registry =
            new ConversationRuntimeStageRegistry(clock::get);

    @Test
    public void consecutiveSameStageForSameTaskIsCoalesced() {
        List<ConversationRuntimeStageRegistry.StageEvent> events = new ArrayList<>();
        registry.setListener(new ConversationRuntimeStageRegistry.Listener() {
            @Override public void onRuntimeStage(
                    ConversationRuntimeStageRegistry.StageEvent event) {
                events.add(event);
            }
        });

        assertTrue(registry.publish("c1", "t1",
                ConversationRuntimeStage.STAGE_QUEUED, ""));
        assertFalse("同任务同阶段的重复发布必须合并（§6.2 高频合并）",
                registry.publish("c1", "t1",
                        ConversationRuntimeStage.STAGE_QUEUED, ""));

        assertEquals(1, events.size());
        assertEquals(1L, events.get(0).generation());
    }

    @Test
    public void stageChangeIncrementsGenerationAndNotifiesImmediately() {
        List<ConversationRuntimeStageRegistry.StageEvent> events = new ArrayList<>();
        registry.setListener(new ConversationRuntimeStageRegistry.Listener() {
            @Override public void onRuntimeStage(
                    ConversationRuntimeStageRegistry.StageEvent event) {
                events.add(event);
            }
        });

        registry.publish("c1", "t1", ConversationRuntimeStage.STAGE_QUEUED, "");
        registry.publish("c1", "t1", ConversationRuntimeStage.STAGE_PLANNING, "");
        registry.publish("c1", "t1", ConversationRuntimeStage.STAGE_EXECUTING, "媒体音量");

        assertEquals(3, events.size());
        assertEquals(2L, events.get(1).generation());
        assertEquals(3L, events.get(2).generation());
        assertEquals("媒体音量", events.get(2).safeLabel());
        assertFalse("实时转换不是快照", events.get(2).snapshot());
    }

    @Test
    public void queuedTaskDoesNotDisplaceExecutingTask() {
        registry.publish("c1", "t1", ConversationRuntimeStage.STAGE_EXECUTING, "媒体音量");
        clock.incrementAndGet();
        registry.publish("c1", "t2", ConversationRuntimeStage.STAGE_QUEUED, "");

        ConversationRuntimeStageRegistry.StageEvent snapshot = registry.snapshotOf("c1");
        assertEquals("执行中的任务必须优先展示", "t1", snapshot.conversationTaskId());
        assertEquals(1L, snapshot.generation());
    }

    @Test
    public void clearingVisibleTaskPromotesLatestQueuedTask() {
        registry.publish("c1", "t1", ConversationRuntimeStage.STAGE_EXECUTING, "媒体音量");
        clock.incrementAndGet();
        registry.publish("c1", "t2", ConversationRuntimeStage.STAGE_QUEUED, "");

        registry.clear("c1", "t1");

        ConversationRuntimeStageRegistry.StageEvent snapshot = registry.snapshotOf("c1");
        assertEquals("t2", snapshot.conversationTaskId());
        assertEquals("排队任务本身的 generation 不因别的任务终态而变化", 1L,
                snapshot.generation());
    }

    @Test
    public void clearOnlyRemovesMatchingTaskAndEmitsCleared() {
        List<String> cleared = new ArrayList<>();
        registry.setListener(new ConversationRuntimeStageRegistry.Listener() {
            @Override public void onStageCleared(String conversationId,
                    String conversationTaskId) {
                cleared.add(conversationTaskId);
            }
        });
        registry.publish("c1", "t1", ConversationRuntimeStage.STAGE_EXECUTING, "媒体音量");

        registry.clear("c1", "t-other"); // 迟到清理不误删后继
        assertEquals("t1", registry.snapshotOf("c1").conversationTaskId());

        registry.clear("c1", "t1");
        assertNull(registry.snapshotOf("c1"));
        assertEquals(List.of("t1"), cleared);
    }

    @Test
    public void snapshotCarriesFlagWithoutNewListenerNotification() {
        List<ConversationRuntimeStageRegistry.StageEvent> events = new ArrayList<>();
        registry.setListener(new ConversationRuntimeStageRegistry.Listener() {
            @Override public void onRuntimeStage(
                    ConversationRuntimeStageRegistry.StageEvent event) {
                events.add(event);
            }
        });
        registry.publish("c1", "t1", ConversationRuntimeStage.STAGE_QUEUED, "");

        ConversationRuntimeStageRegistry.StageEvent snapshot = registry.snapshotOf("c1");
        assertTrue(snapshot.snapshot());
        assertEquals("读取快照不产生新的实时事件", 1, events.size());
    }
}
