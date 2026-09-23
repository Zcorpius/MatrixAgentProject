package com.matrix.agent.launcher.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.launcher.presentation.ConversationViewModel.PttEvent;
import com.matrix.agent.launcher.presentation.ConversationViewModel.PttPhase;

import org.junit.Test;

import java.util.List;

/** Cancellation must target a primary task, never an attached REPROMPT row. */
public final class ConversationViewModelTest {

    @Test public void latestCancellablePrimarySkipsSteerAndTerminalRows() {
        ConversationViewModel.UiMessage primary = row("primary", 1,
                ConversationMessage.INPUT_PRIMARY, ConversationMessage.STATUS_RUNNING);
        ConversationViewModel.UiMessage steer = row("steer", 2,
                ConversationMessage.INPUT_STEER, ConversationMessage.STATUS_RUNNING);
        ConversationViewModel.UiMessage completed = row("completed", 3,
                ConversationMessage.INPUT_PRIMARY, ConversationMessage.STATUS_COMPLETED);

        ConversationViewModel.UiMessage selected =
                ConversationViewModel.latestCancellablePrimary(List.of(primary, steer, completed));

        assertEquals("primary", selected.messageId());
    }

    @Test public void noPrimaryTaskMeansNothingIsCancellable() {
        assertNull(ConversationViewModel.latestCancellablePrimary(List.of(
                row("steer", 1, ConversationMessage.INPUT_STEER,
                        ConversationMessage.STATUS_RUNNING))));
    }

    /** 快乐路径：按下（ARMING）→ Host 确认采音 → 松开 → 消息落库/会话终结。 */
    @Test public void pttHappyPathAdvancesThroughAllPhases() {
        PttPhase phase = PttPhase.ARMING;
        phase = ConversationViewModel.nextPhase(phase, PttEvent.CAPTURE_STARTED);
        assertEquals(PttPhase.LISTENING, phase);
        phase = ConversationViewModel.nextPhase(phase, PttEvent.RELEASED);
        assertEquals(PttPhase.PROCESSING, phase);
        phase = ConversationViewModel.nextPhase(phase, PttEvent.TERMINATED);
        assertEquals(PttPhase.IDLE, phase);
    }

    /** 采音确认与转写互为证据：IDLE/PROCESSING/LISTENING 收到 CAPTURE_STARTED 不得改变阶段。 */
    @Test public void captureConfirmationNeverRegressesThePhase() {
        assertEquals(PttPhase.IDLE, ConversationViewModel.nextPhase(
                PttPhase.IDLE, PttEvent.CAPTURE_STARTED));
        assertEquals(PttPhase.PROCESSING, ConversationViewModel.nextPhase(
                PttPhase.PROCESSING, PttEvent.CAPTURE_STARTED));
        assertEquals(PttPhase.LISTENING, ConversationViewModel.nextPhase(
                PttPhase.LISTENING, PttEvent.CAPTURE_STARTED));
    }

    /** 松开只在仍按住（ARMING/LISTENING）时有效；空闲或已松开后重复松开是 no-op。 */
    @Test public void releaseOnlyEndsAnHeldSession() {
        assertEquals(PttPhase.PROCESSING, ConversationViewModel.nextPhase(
                PttPhase.ARMING, PttEvent.RELEASED));
        assertEquals(PttPhase.PROCESSING, ConversationViewModel.nextPhase(
                PttPhase.LISTENING, PttEvent.RELEASED));
        assertEquals(PttPhase.IDLE, ConversationViewModel.nextPhase(
                PttPhase.IDLE, PttEvent.RELEASED));
        assertEquals(PttPhase.PROCESSING, ConversationViewModel.nextPhase(
                PttPhase.PROCESSING, PttEvent.RELEASED));
    }

    /** 终结事件从任何阶段收敛回 IDLE；final 已产出只把 LISTENING 推进到提交收敛。 */
    @Test public void terminalAndFinalUnderwayTransitions() {
        for (PttPhase phase : PttPhase.values()) {
            assertEquals(PttPhase.IDLE, ConversationViewModel.nextPhase(
                    phase, PttEvent.TERMINATED));
        }
        assertEquals(PttPhase.PROCESSING, ConversationViewModel.nextPhase(
                PttPhase.LISTENING, PttEvent.FINAL_UNDERWAY));
        assertEquals(PttPhase.ARMING, ConversationViewModel.nextPhase(
                PttPhase.ARMING, PttEvent.FINAL_UNDERWAY));
        assertEquals(PttPhase.IDLE, ConversationViewModel.nextPhase(
                PttPhase.IDLE, PttEvent.FINAL_UNDERWAY));
        assertEquals(PttPhase.PROCESSING, ConversationViewModel.nextPhase(
                PttPhase.PROCESSING, PttEvent.FINAL_UNDERWAY));
    }

    private static ConversationViewModel.UiMessage row(String id, long sequence, int inputKind,
            int status) {
        return new ConversationViewModel.UiMessage(id, sequence, ConversationMessage.ROLE_USER,
                status, ConversationMessage.CHANNEL_TEXT, "text", 0, inputKind, null,
                ConversationMessage.STEER_DELIVERY_PENDING, "task", List.of(), List.of());
    }
}
