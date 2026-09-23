package com.matrix.agent.task.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.conversation.CapabilityExecutionTrace;
import com.matrix.agent.conversation.ConversationExecutionReplyProjector;

import org.junit.Test;

/** AssistantReply 投影契约（设计文档 §9.2 / §11.1 AssistantReply 行）。 */
public final class ConversationAssistantProjectorTest {

    private static AgentOutcome outcome(TaskState state, StopReason reason, String finalText) {
        Trajectory trajectory = new Trajectory();
        trajectory.finish(reason, 10L, 0);
        return new AgentOutcome("req", state, reason, trajectory, 10L, java.util.List.of(),
                finalText);
    }

    @Test public void modelFinalPassesThroughSanitized() {
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL, "已将音量调整为 35%。"),
                2000);
        assertEquals(AssistantReply.Source.MODEL_FINAL, reply.source());
        assertEquals("已将音量调整为 35%。", reply.text());
        assertFalse(reply.truncated());
        assertTrue(reply.displaySafe());
    }

    @Test public void modelFinalTruncatesAtCapAndMarksTruncated() {
        String longText = "x".repeat(3000);
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL, longText), 2000);
        assertEquals(AssistantReply.Source.MODEL_FINAL, reply.source());
        assertTrue(reply.truncated());
        assertTrue(reply.text().length() <= 2000);
    }

    @Test public void credentialsInModelFinalAreMasked() {
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL,
                        "key sk-abcdef0123456789abcdef 已配置"),
                2000);
        assertFalse(reply.text().contains("sk-abcdef0123456789abcdef"));
        assertTrue(reply.text().contains("***"));
    }

    @Test public void noTrustedTextSynthesizesExplanation() {
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.FAILED, StopReason.POLICY_HALT, null), 2000);
        assertEquals(AssistantReply.Source.SYNTHESIZED_TERMINAL, reply.source());
        assertEquals("该请求因安全策略未执行。", reply.text());
    }

    @Test public void networkUnavailableDoesNotClaimTimeout() {
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.NETWORK_UNAVAILABLE, StopReason.NETWORK_UNAVAILABLE, null),
                2000);
        assertEquals("无法连接云端模型，请检查网络后重试。", reply.text());
    }

    @Test public void executionUnknownDoesNotClaimFailureOrCancel() {
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.EXECUTION_UNKNOWN, StopReason.EXECUTION_UNKNOWN, null), 2000);
        assertEquals("指令可能已发出，但执行结果未知；请查看设备当前状态确认。", reply.text());
    }

    @Test public void lengthExceededNeverPosesAsCompleteReply() {
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.FAILED, StopReason.LENGTH_EXCEEDED,
                        "半截文本"), 2000);
        assertEquals(AssistantReply.Source.SYNTHESIZED_TERMINAL, reply.source());
        assertEquals("模型输出被截断，未采纳为完整回复。", reply.text());
    }

    @Test public void cancelledSynthesizesNotFakes() {
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.CANCELLED, StopReason.CANCELLED, null), 2000);
        assertEquals("任务已取消。", reply.text());
    }

    @Test public void blankModelFinalSynthesizes() {
        AssistantReply reply = ConversationAssistantProjector.project(
                outcome(TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL, "   "), 2000);
        assertEquals(AssistantReply.Source.SYNTHESIZED_TERMINAL, reply.source());
    }

    @Test public void genericCompletionIsReplacedByVerifiedHostFacts() {
        AssistantReply reply = ConversationExecutionReplyProjector.replaceGenericCompletion(
                ConversationAssistantProjector.project(
                        outcome(TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL, "任务完成"), 2000),
                CapabilityExecutionTrace.listOf(new CapabilityExecutionTrace(
                        "system.media.set_volume", "调整媒体音量",
                        CapabilityExecutionTrace.OUTCOME_SUCCESS, "percent=35", "percent=33",
                        CapabilityExecutionTrace.VERIFY_MISMATCH)));

        assertEquals(AssistantReply.Source.HOST_FACTS, reply.source());
        assertEquals("已将媒体音量设置为35%，设备当前为33%。", reply.text());
    }

    @Test public void meaningfulModelFinalIsNotOverwrittenByHostFacts() {
        AssistantReply original = ConversationAssistantProjector.project(
                outcome(TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL, "已经为你调整好了音量。"),
                2000);
        AssistantReply reply = ConversationExecutionReplyProjector.replaceGenericCompletion(original,
                CapabilityExecutionTrace.listOf(new CapabilityExecutionTrace(
                        "system.media.set_volume", "调整媒体音量",
                        CapabilityExecutionTrace.OUTCOME_SUCCESS, "percent=35", "percent=33",
                        CapabilityExecutionTrace.VERIFY_MISMATCH)));
        assertEquals(original, reply);
    }

    @Test public void replyRejectsBlankText() {
        assertThrows(IllegalArgumentException.class,
                () -> AssistantReply.modelFinal("  ", false));
        assertThrows(IllegalArgumentException.class, () -> AssistantReply.synthesized(""));
    }
}
