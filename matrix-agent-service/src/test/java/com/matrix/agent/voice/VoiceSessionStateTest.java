package com.matrix.agent.voice;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link VoiceSessionState} 状态机测试。
 *
 * <p>覆盖:正常流迁移、Demo CONFIRMING 不可达、barge-in、各状态取消 / 错误、非法事件容错。
 */
public final class VoiceSessionStateTest {

    // ---- 正常流 ----

    @Test
    public void idle_wake_goesToWakeAccepted() {
        VoiceSessionState s = new VoiceSessionState();
        assertEquals(VoiceSessionState.State.WAKE_ACCEPTED, s.transit(VoiceEvent.wake()));
    }

    @Test
    public void wakeAccepted_captureStarted_goesToListening() {
        VoiceSessionState s = reach(VoiceSessionState.State.WAKE_ACCEPTED);
        assertEquals(VoiceSessionState.State.LISTENING, s.transit(VoiceEvent.captureStarted()));
    }

    @Test
    public void listening_partialAndSpeech_stayInListening() {
        VoiceSessionState s = reach(VoiceSessionState.State.LISTENING);
        assertEquals(VoiceSessionState.State.LISTENING, s.transit(VoiceEvent.partial("嗯", 0.5f)));
        assertEquals(VoiceSessionState.State.LISTENING, s.transit(VoiceEvent.speech()));
    }

    @Test
    public void listening_silenceTimeout_goesToEndpointing() {
        VoiceSessionState s = reach(VoiceSessionState.State.LISTENING);
        assertEquals(VoiceSessionState.State.ENDPOINTING, s.transit(VoiceEvent.silenceTimeout()));
    }

    @Test
    public void endpointing_final_goesToRecognizing() {
        VoiceSessionState s = reach(VoiceSessionState.State.ENDPOINTING);
        assertEquals(VoiceSessionState.State.RECOGNIZING,
                s.transit(VoiceEvent.finalTranscript("开空调", 0.92f)));
    }

    @Test
    public void recognizing_accepted_goesToThinking() {
        VoiceSessionState s = reach(VoiceSessionState.State.RECOGNIZING);
        assertEquals(VoiceSessionState.State.THINKING, s.transit(VoiceEvent.accepted()));
    }

    @Test
    public void recognizing_rejected_goesBackToListening() {
        VoiceSessionState s = reach(VoiceSessionState.State.RECOGNIZING);
        assertEquals(VoiceSessionState.State.LISTENING, s.transit(VoiceEvent.rejected()));
    }

    @Test
    public void thinking_terminal_goesToSpeaking() {
        VoiceSessionState s = reach(VoiceSessionState.State.THINKING);
        assertEquals(VoiceSessionState.State.SPEAKING, s.transit(VoiceEvent.terminal()));
    }

    @Test
    public void speaking_ttsDone_goesToIdle() {
        VoiceSessionState s = reach(VoiceSessionState.State.SPEAKING);
        assertEquals(VoiceSessionState.State.IDLE, s.transit(VoiceEvent.ttsDone()));
    }

    @Test
    public void speaking_bargeIn_goesToBargeInListening_thenListening() {
        VoiceSessionState s = reach(VoiceSessionState.State.SPEAKING);
        assertEquals(VoiceSessionState.State.BARGE_IN_LISTENING, s.transit(VoiceEvent.bargeIn()));
        assertEquals(VoiceSessionState.State.LISTENING, s.transit(VoiceEvent.captureStarted()));
    }

    @Test
    public void speaking_focusLoss_goesToIdle() {
        VoiceSessionState s = reach(VoiceSessionState.State.SPEAKING);
        assertEquals(VoiceSessionState.State.IDLE, s.transit(VoiceEvent.focusLoss()));
    }

    // ---- Demo 约束:CONFIRMING 不可达 ----

    @Test
    public void needsConfirm_recognizing_goesToIdle_bypassingConfirming() {
        VoiceSessionState s = reach(VoiceSessionState.State.RECOGNIZING);
        assertEquals("Demo 遇 USER_CONFIRM 直接拒绝,绕过 CONFIRMING",
                VoiceSessionState.State.IDLE, s.transit(VoiceEvent.needsConfirm()));
    }

    @Test
    public void needsConfirm_thinking_goesToIdle_bypassingConfirming() {
        VoiceSessionState s = reach(VoiceSessionState.State.THINKING);
        assertEquals(VoiceSessionState.State.IDLE, s.transit(VoiceEvent.needsConfirm()));
    }

    @Test
    public void confirming_isUnreachableInDemo() {
        EnumSet<VoiceSessionState.State> starts = EnumSet.allOf(VoiceSessionState.State.class);
        starts.remove(VoiceSessionState.State.CONFIRMING); // 起点本身不可达
        for (VoiceSessionState.State start : starts) {
            for (VoiceEvent.EventType type : VoiceEvent.EventType.values()) {
                VoiceSessionState s = reach(start);
                VoiceSessionState.State after = s.transit(eventFor(type));
                assertTrue("start=" + start + " event=" + type + " 不应进入 CONFIRMING,实际=" + after,
                        after != VoiceSessionState.State.CONFIRMING);
            }
        }
    }

    // ---- 取消 / 错误 ----

    @Test
    public void cancel_fromEachState_goesToCancelled_thenReset_toIdle() {
        VoiceSessionState.State[] starts = {
                VoiceSessionState.State.WAKE_ACCEPTED,
                VoiceSessionState.State.LISTENING,
                VoiceSessionState.State.RECOGNIZING,
                VoiceSessionState.State.THINKING,
                VoiceSessionState.State.SPEAKING
        };
        for (VoiceSessionState.State start : starts) {
            VoiceSessionState s = reach(start);
            assertEquals("start=" + start, VoiceSessionState.State.CANCELLED, s.transit(VoiceEvent.cancel()));
            assertEquals("start=" + start, VoiceSessionState.State.IDLE, s.transit(VoiceEvent.reset()));
        }
    }

    @Test
    public void error_fromAnyState_goesToErrorAnnouncing_thenReset_toIdle() {
        VoiceSessionState s = reach(VoiceSessionState.State.LISTENING);
        assertEquals(VoiceSessionState.State.ERROR_ANNOUNCING, s.transit(VoiceEvent.error("E")));
        assertEquals(VoiceSessionState.State.IDLE, s.transit(VoiceEvent.reset()));
    }

    // ---- 非法事件 ----

    @Test
    public void illegalEvent_returnsSameState_andDoesNotThrow() {
        VoiceSessionState s = new VoiceSessionState(); // IDLE
        assertEquals("IDLE 收 TTS_DONE 应留原态",
                VoiceSessionState.State.IDLE, s.transit(VoiceEvent.ttsDone()));
        assertEquals(VoiceSessionState.State.IDLE, s.transit(VoiceEvent.terminal()));
        assertEquals(VoiceSessionState.State.IDLE, s.transit(VoiceEvent.bargeIn()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullEvent_rejected() {
        new VoiceSessionState().transit(null);
    }

    // ---- helpers ----

    /** 推进一个新实例到目标状态(合法路径)。CONFIRMING 不可达,调用方不应请求。 */
    private VoiceSessionState reach(VoiceSessionState.State target) {
        VoiceSessionState s = new VoiceSessionState();
        switch (target) {
            case IDLE:
                break;
            case WAKE_ACCEPTED:
                s.transit(VoiceEvent.wake());
                break;
            case LISTENING:
                s.transit(VoiceEvent.wake());
                s.transit(VoiceEvent.captureStarted());
                break;
            case ENDPOINTING:
                s.transit(VoiceEvent.wake());
                s.transit(VoiceEvent.captureStarted());
                s.transit(VoiceEvent.silenceTimeout());
                break;
            case RECOGNIZING:
                s.transit(VoiceEvent.wake());
                s.transit(VoiceEvent.captureStarted());
                s.transit(VoiceEvent.silenceTimeout());
                s.transit(VoiceEvent.finalTranscript("x", 0.9f));
                break;
            case THINKING:
                s.transit(VoiceEvent.wake());
                s.transit(VoiceEvent.captureStarted());
                s.transit(VoiceEvent.silenceTimeout());
                s.transit(VoiceEvent.finalTranscript("x", 0.9f));
                s.transit(VoiceEvent.accepted());
                break;
            case SPEAKING:
                s.transit(VoiceEvent.wake());
                s.transit(VoiceEvent.captureStarted());
                s.transit(VoiceEvent.silenceTimeout());
                s.transit(VoiceEvent.finalTranscript("x", 0.9f));
                s.transit(VoiceEvent.accepted());
                s.transit(VoiceEvent.terminal());
                break;
            case BARGE_IN_LISTENING:
                s.transit(VoiceEvent.wake());
                s.transit(VoiceEvent.captureStarted());
                s.transit(VoiceEvent.silenceTimeout());
                s.transit(VoiceEvent.finalTranscript("x", 0.9f));
                s.transit(VoiceEvent.accepted());
                s.transit(VoiceEvent.terminal());
                s.transit(VoiceEvent.bargeIn());
                break;
            case CANCELLED:
                s.transit(VoiceEvent.wake());
                s.transit(VoiceEvent.cancel());
                break;
            case ERROR_ANNOUNCING:
                s.transit(VoiceEvent.wake());
                s.transit(VoiceEvent.error("E"));
                break;
            default:
                throw new IllegalArgumentException("reach 不支持: " + target);
        }
        assertEquals("reach 应到达 " + target, target, s.current());
        return s;
    }

    private VoiceEvent eventFor(VoiceEvent.EventType type) {
        switch (type) {
            case WAKE: return VoiceEvent.wake();
            case CAPTURE_STARTED: return VoiceEvent.captureStarted();
            case PARTIAL: return VoiceEvent.partial("x", 0.5f);
            case SPEECH: return VoiceEvent.speech();
            case SILENCE_TIMEOUT: return VoiceEvent.silenceTimeout();
            case FINAL: return VoiceEvent.finalTranscript("x", 0.9f);
            case ASR_ERROR: return VoiceEvent.asrError("E");
            case ACCEPTED: return VoiceEvent.accepted();
            case REJECTED: return VoiceEvent.rejected();
            case NEEDS_CONFIRM: return VoiceEvent.needsConfirm();
            case TERMINAL: return VoiceEvent.terminal();
            case CANCEL: return VoiceEvent.cancel();
            case BARGE_IN: return VoiceEvent.bargeIn();
            case TTS_DONE: return VoiceEvent.ttsDone();
            case FOCUS_LOSS: return VoiceEvent.focusLoss();
            case ERROR: return VoiceEvent.error("E");
            case RESET: return VoiceEvent.reset();
            default: throw new IllegalArgumentException(type.name());
        }
    }
}
