package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;

import com.matrix.agent.api.conversation.ConversationRuntimeStage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** task → conversation 进度桥（I3 §6.2）：未绑定丢弃、标签投影、unbind 后静默。 */
public final class ConversationTaskProgressBridgeTest {

    private final ConversationRuntimeStageRegistry registry =
            new ConversationRuntimeStageRegistry(() -> 1L);
    private final ConversationTaskProgressBridge bridge =
            new ConversationTaskProgressBridge(registry, label -> "执行:" + label);

    @Test
    public void unboundRequestIdIsSilentlyDropped() {
        List<ConversationRuntimeStageRegistry.StageEvent> events = new ArrayList<>();
        registry.setListener(new ConversationRuntimeStageRegistry.Listener() {
            @Override public void onRuntimeStage(
                    ConversationRuntimeStageRegistry.StageEvent event) {
                events.add(event);
            }
        });

        bridge.onModelPlanning("unknown-request");

        assertEquals("durable/非对话任务的事件不得进入会话阶段", 0, events.size());
    }

    @Test
    public void boundRequestPublishesPlanningAndExecutingWithFriendlyLabel() {
        List<ConversationRuntimeStageRegistry.StageEvent> events = new ArrayList<>();
        registry.setListener(new ConversationRuntimeStageRegistry.Listener() {
            @Override public void onRuntimeStage(
                    ConversationRuntimeStageRegistry.StageEvent event) {
                events.add(event);
            }
        });
        bridge.bind("req-1", "c1", "t1");

        bridge.onModelPlanning("req-1");
        bridge.onCapabilityExecuting("req-1", "system.media.set_volume");

        assertEquals(2, events.size());
        assertEquals(ConversationRuntimeStage.STAGE_PLANNING, events.get(0).stage());
        assertEquals(ConversationRuntimeStage.STAGE_EXECUTING, events.get(1).stage());
        assertEquals("标签经装配方注入的友好名投影", "执行:system.media.set_volume",
                events.get(1).safeLabel());
    }

    @Test
    public void unbindStopsFurtherPublications() {
        bridge.bind("req-1", "c1", "t1");
        bridge.unbind("req-1");

        List<ConversationRuntimeStageRegistry.StageEvent> events = new ArrayList<>();
        registry.setListener(new ConversationRuntimeStageRegistry.Listener() {
            @Override public void onRuntimeStage(
                    ConversationRuntimeStageRegistry.StageEvent event) {
                events.add(event);
            }
        });
        bridge.onModelPlanning("req-1");

        assertEquals(0, events.size());
    }
}
