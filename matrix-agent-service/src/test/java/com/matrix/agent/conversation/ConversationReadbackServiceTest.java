package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.conversation.ConversationDomain.PersistedMessageStatus;
import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.port.AudioFocusPort;
import com.matrix.agent.voice.port.ManagedTtsPort;
import com.matrix.agent.voice.port.TtsPort;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Readback owns its route lifecycle: cloud credential changes cannot leak an old TTS engine. */
public final class ConversationReadbackServiceTest {
    private static final String CONVERSATION = "conversation";
    private static final String OWNER = "driver";
    private static final String ASSISTANT = "assistant";

    @Test public void refreshReplacesOutputAndClosePreventsRouteResurrection() {
        FakeConversationStore store = seededStore();
        FakeFocus focus = new FakeFocus();
        List<FakeOutput> outputs = new ArrayList<>();
        ConversationReadbackService service = new ConversationReadbackService(store, () -> {
            FakeOutput output = new FakeOutput();
            outputs.add(output);
            return output;
        }, focus, () -> false);

        assertEquals(1, outputs.size());
        service.refreshOutputRoute();
        assertEquals(2, outputs.size());
        assertEquals(1, outputs.get(0).stopCalls);
        assertEquals(1, outputs.get(0).shutdownCalls);

        service.close();
        assertEquals(1, outputs.get(1).shutdownCalls);
        service.refreshOutputRoute();
        assertEquals("close 后不得重新创建输出引擎", 2, outputs.size());
    }

    @Test public void speakUsesCurrentRouteAndCompletionReleasesFocus() {
        FakeConversationStore store = seededStore();
        FakeFocus focus = new FakeFocus();
        FakeOutput output = new FakeOutput();
        ConversationReadbackService service = new ConversationReadbackService(store,
                () -> output, focus, () -> false);

        assertNull(service.speak(CONVERSATION, ASSISTANT, OWNER));
        assertEquals(1, focus.requestCalls);
        assertEquals("播报正文", output.lastResponse.getText());
        output.listener.onDone(output.lastUtteranceId);
        assertEquals(1, focus.releaseCalls);
        service.close();
    }

    @Test public void missingConversationIsIndistinguishableFromMissingMessage() {
        FakeConversationStore store = seededStore();
        // Models the deletion race between the message lookup and its owner lookup.  The public
        // binder contract must fail closed instead of dereferencing a stale conversation row.
        store.conversations.clear();
        ConversationReadbackService service = new ConversationReadbackService(store,
                FakeOutput::new, new FakeFocus(), () -> false);

        assertEquals(ConversationReadbackService.ERR_NOT_FOUND,
                service.speak(CONVERSATION, ASSISTANT, OWNER));
        service.close();
    }

    private static FakeConversationStore seededStore() {
        FakeConversationStore store = new FakeConversationStore();
        store.seedConversation(CONVERSATION, OWNER);
        store.messages.put(ASSISTANT, new ConversationStore.MessageRow(ASSISTANT, CONVERSATION,
                1L, ConversationMessage.ROLE_ASSISTANT,
                PersistedMessageStatus.COMPLETED.wire(), ConversationMessage.CHANNEL_TEXT,
                "播报正文", "zh-CN", null, 0, 1L, 1L,
                ConversationStore.MessageRow.INPUT_PRIMARY_WIRE, null,
                ConversationStore.MessageRow.STEER_DELIVERY_NONE_WIRE));
        return store;
    }

    private static final class FakeFocus implements AudioFocusPort {
        int requestCalls;
        int releaseCalls;
        Listener listener;
        @Override public void setListener(Listener listener) { this.listener = listener; }
        @Override public boolean request() { requestCalls++; return true; }
        @Override public void release() { releaseCalls++; }
    }

    private static final class FakeOutput implements ManagedTtsPort {
        int stopCalls;
        int shutdownCalls;
        TtsPort.Listener listener;
        SpeakableResponse lastResponse;
        String lastUtteranceId;

        @Override public void setListener(TtsPort.Listener listener) { this.listener = listener; }
        @Override public void speak(SpeakableResponse response, String utteranceId) {
            lastResponse = response;
            lastUtteranceId = utteranceId;
        }
        @Override public void stop() { stopCalls++; }
        @Override public void shutdown() { shutdownCalls++; }
    }
}
