package com.matrix.agent.api.common;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import com.matrix.agent.api.agent.AgentTaskEvent;

import org.junit.Test;

/** Golden assignments: published values are append-only and must never be renumbered. */
public final class PublicAbiGoldenTest {
    @Test public void taskStatesRemainFrozen() {
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, new int[] {
                AgentTaskState.ACCEPTED, AgentTaskState.RUNNING,
                AgentTaskState.WAITING_CONFIRMATION, AgentTaskState.DEFERRED,
                AgentTaskState.COMPLETED, AgentTaskState.PARTIALLY_COMPLETED,
                AgentTaskState.REJECTED, AgentTaskState.FAILED,
                AgentTaskState.CANCELLED, AgentTaskState.EXECUTION_UNKNOWN});
    }

    @Test public void errorCodesRemainFrozen() {
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 17},
                new int[] {MatrixErrorCode.SUCCESS, MatrixErrorCode.PERMISSION_DENIED,
                        MatrixErrorCode.SERVICE_NOT_READY,
                        MatrixErrorCode.IDEMPOTENCY_CONFLICT, MatrixErrorCode.TIMED_OUT,
                        MatrixErrorCode.OVERLOADED, MatrixErrorCode.SERVICE_RESTARTED,
                        MatrixErrorCode.TOO_LATE, MatrixErrorCode.PERSISTENCE_UNAVAILABLE,
                        MatrixErrorCode.INVALID_ARGUMENT, MatrixErrorCode.NOT_FOUND,
                        MatrixErrorCode.UNSUPPORTED_OPERATION, MatrixErrorCode.TASK_FAILED,
                        MatrixErrorCode.CONTRACT_MISMATCH,
                        MatrixErrorCode.VOICE_OUTPUT_UNAVAILABLE,
                        MatrixErrorCode.PROCESS_INTERRUPTED,
                        MatrixErrorCode.INVALID_STATE,
                        MatrixErrorCode.SERVICE_VERSION_UNSUPPORTED});
    }

    @Test public void conversationInputContractRemainFrozen() {
        // 输入交互增强 v7：submitTextOrAppend 判定结果与运行阶段 wire 值追加即冻结。
        assertArrayEquals(new int[] {0, 1, 2, 3, 4}, new int[] {
                com.matrix.agent.api.conversation.ConversationSubmission.OUTCOME_UNSPECIFIED,
                com.matrix.agent.api.conversation.ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED,
                com.matrix.agent.api.conversation.ConversationSubmission.OUTCOME_STEER_ACCEPTED,
                com.matrix.agent.api.conversation.ConversationSubmission.OUTCOME_INVALID_STATE,
                com.matrix.agent.api.conversation.ConversationSubmission.OUTCOME_REJECTED});
        assertArrayEquals(new int[] {1, 2, 3}, new int[] {
                com.matrix.agent.api.conversation.ConversationRuntimeStage.STAGE_QUEUED,
                com.matrix.agent.api.conversation.ConversationRuntimeStage.STAGE_PLANNING,
                com.matrix.agent.api.conversation.ConversationRuntimeStage.STAGE_EXECUTING});
    }

    @Test public void attachmentContractRemainsFrozen() {
        // 输入交互增强 v8：附件 state/error wire 值与发现键/特性位追加即冻结。
        assertArrayEquals(new int[] {1, 2}, new int[] {
                com.matrix.agent.api.conversation.ConversationAttachment.STATE_READY,
                com.matrix.agent.api.conversation.ConversationAttachment.STATE_FAILED});
        assertArrayEquals(new int[] {20, 21, 22}, new int[] {
                com.matrix.agent.api.conversation.ConversationAttachment
                        .ERROR_UNSUPPORTED_MEDIA,
                com.matrix.agent.api.conversation.ConversationAttachment.ERROR_TOO_LARGE,
                com.matrix.agent.api.conversation.ConversationAttachment.ERROR_EMPTY_TEXT});
        assertEquals("matrix.service.ATTACHMENT", MatrixServiceConstants.ATTACHMENT_SERVICE);
        assertEquals(64, MatrixServiceConstants.FEATURE_ATTACHMENT_DOMAIN);
    }

    @Test public void eventsAndFeatureBitsRemainFrozen() {
        assertArrayEquals(new int[] {1, 2, 3, 4}, new int[] {
                AgentTaskEvent.TYPE_STATE_CHANGED, AgentTaskEvent.TYPE_TEXT_DELTA,
                AgentTaskEvent.TYPE_CONFIRMATION_REQUEST, AgentTaskEvent.TYPE_RESYNC_REQUIRED});
        assertEquals(1, MatrixServiceConstants.FEATURE_DURABLE_TASKS);
        assertEquals(2, MatrixServiceConstants.FEATURE_MODEL_DOMAIN);
        assertEquals(4, MatrixServiceConstants.FEATURE_DOWNLOAD_DOMAIN);
        assertEquals(8, MatrixServiceConstants.FEATURE_VOICE_DOMAIN);
        assertEquals(16, MatrixServiceConstants.FEATURE_PERSISTENCE_GATE);
        assertEquals(31, MatrixServiceConstants.ALL_STAGE_B_FEATURES);
        assertEquals("matrix_agent_service", MatrixServiceConstants.MATRIX_AGENT_SERVICE);
        assertEquals("matrix.service.MANAGER", MatrixServiceConstants.MANAGER_SERVICE);
        assertEquals("matrix.service.MODEL", MatrixServiceConstants.MODEL_SERVICE);
        assertEquals("matrix.service.VOICE", MatrixServiceConstants.VOICE_SERVICE);
        assertEquals("matrix.service.DOWNLOAD", MatrixServiceConstants.DOWNLOAD_SERVICE);
    }
}
