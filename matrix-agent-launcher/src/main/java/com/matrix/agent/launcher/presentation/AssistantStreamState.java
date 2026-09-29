package com.matrix.agent.launcher.presentation;

import com.matrix.agent.api.conversation.ConversationAssistantStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

/** Client-side sequence/tombstone gate. Reconnect intentionally starts with no transient body. */
public final class AssistantStreamState {
    private final LinkedHashMap<String, Long> sequences = new LinkedHashMap<>();
    private final LinkedHashSet<String> terminal = new LinkedHashSet<>();
    private ConversationAssistantStream current;
    public boolean accept(ConversationAssistantStream event) {
        if (event == null || event.text == null || event.text.length() > 8192 || event.turn < 0
                || event.sequence <= 0 || event.runtimeRequestId == null || event.conversationTaskId == null
                || terminal.contains(event.conversationTaskId)
                || event.sequence <= sequences.getOrDefault(event.runtimeRequestId, 0L)) return false;
        sequences.put(event.runtimeRequestId, event.sequence);
        while (sequences.size() > 128) sequences.remove(sequences.keySet().iterator().next());
        if (event.cleared) {
            if (current != null && current.runtimeRequestId.equals(event.runtimeRequestId)) current = null;
        } else current = event;
        return true;
    }
    public void terminal(String task) {
        if (task == null) return;
        terminal.add(task);
        while (terminal.size() > 128) terminal.remove(terminal.iterator().next());
        if (current != null && current.conversationTaskId.equals(task)) current = null;
    }
    public String text() { return current == null ? "" : current.text; }
    public void clear() { current = null; sequences.clear(); terminal.clear(); }
}
