package com.matrix.agent.conversation;

import com.matrix.agent.contract.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public final class AssistantStreamBridgeTest {
    @Test public void coalescesBoundsAndRejectsLateTurnsAndTerminalEvents() {
        var bridge = new ConversationTaskProgressBridge(new ConversationRuntimeStageRegistry(), value -> value);
        List<AssistantStreamEvent> events = new ArrayList<>();
        bridge.setAssistantStreamSink(events::add);
        bridge.bind("request", "conversation", "task");
        bridge.onAssistantStreamStarted("request", 1);
        for (int i = 0; i < 1000; i++) bridge.onAssistantStreamEvent("request", 1, new ModelStreamEvent.BodyDelta("正文"));
        bridge.onAssistantStreamEvent("request", 1, new ModelStreamEvent.Completed(FinishReason.STOP));
        assertEquals("正文".repeat(1000), events.get(events.size()-1).text());
        assertTrue(events.size() < 50);
        int before = events.size();
        bridge.onAssistantStreamEvent("request", 1, new ModelStreamEvent.BodyDelta("late"));
        assertEquals(before, events.size());
        bridge.onAssistantStreamStarted("request", 2);
        bridge.onAssistantStreamEvent("request", 1, new ModelStreamEvent.BodyDelta("old turn"));
        bridge.onAssistantStreamEvent("request", 2, new ModelStreamEvent.BodyDelta("A".repeat(9000)));
        assertEquals(8192, events.get(events.size()-1).text().length());
        bridge.unbind("request");
        assertTrue(events.get(events.size()-1).cleared());
        before = events.size();
        bridge.onAssistantStreamEvent("request", 2, new ModelStreamEvent.BodyDelta("after terminal"));
        assertEquals(before, events.size());
        long sequence = 0;
        for (var event : events) { assertTrue(event.sequence() > sequence); sequence = event.sequence(); }
    }
}
