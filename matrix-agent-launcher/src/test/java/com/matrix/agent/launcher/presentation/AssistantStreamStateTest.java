package com.matrix.agent.launcher.presentation;

import com.matrix.agent.api.conversation.ConversationAssistantStream;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AssistantStreamStateTest {
    @Test public void duplicateLateAndTerminalEventsCannotResurrectBody() {
        var state = new AssistantStreamState();
        assertTrue(state.accept(event(2, "正在写", false)));
        assertFalse(state.accept(event(1, "旧片段", false)));
        assertFalse(state.accept(event(2, "重复", false)));
        assertTrue(state.accept(event(3, "", true)));
        assertEquals("", state.text());
        assertFalse(state.accept(event(2, "晚到", false)));
        state.terminal("task");
        assertFalse(state.accept(event(4, "终态后晚到", false)));
        assertEquals("", state.text());
    }
    private static ConversationAssistantStream event(long sequence, String text, boolean cleared) {
        return new ConversationAssistantStream("conversation", "task", "request", 1, sequence, text, cleared);
    }
}
