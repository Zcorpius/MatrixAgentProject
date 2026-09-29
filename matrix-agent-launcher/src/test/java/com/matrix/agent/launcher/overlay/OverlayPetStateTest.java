package com.matrix.agent.launcher.overlay;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.matrix.agent.api.conversation.ConversationMessage.*;
import static com.matrix.agent.api.conversation.ConversationRuntimeStage.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Motion.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Indicator.*;

public final class OverlayPetStateTest {
    @Test public void queuedPlanningAndExecutionUseStructuredStages() {
        assertEquals(WAITING, OverlayPetState.from(STATUS_RUNNING, STAGE_QUEUED, true, false).motion());
        assertEquals(WORKING, OverlayPetState.from(STATUS_ACCEPTED, STAGE_PLANNING, true, false).motion());
        assertEquals(WORKING, OverlayPetState.from(STATUS_RUNNING, STAGE_EXECUTING, true, false).motion());
        assertEquals(WAITING, OverlayPetState.from(-1, -1, true, false).motion());
    }

    @Test public void disconnectionAndCancellationDoNotPretendWorkIsContinuing() {
        var offline = OverlayPetState.from(STATUS_RUNNING, STAGE_EXECUTING, false, false);
        assertEquals(WAITING, offline.motion());
        assertEquals(OFFLINE, offline.indicator());
        assertEquals(WAITING, OverlayPetState.from(STATUS_RUNNING, STAGE_EXECUTING, true, true).motion());
    }

    @Test public void terminalFactsSurviveReconnectionWithoutReplayingAnimations() {
        for (int status : new int[]{STATUS_COMPLETED, STATUS_FAILED, STATUS_REJECTED,
                STATUS_EXECUTION_UNKNOWN, STATUS_CANCELLED}) {
            assertEquals(OverlayPetState.from(status, -1, true, false),
                    OverlayPetState.from(status, STAGE_EXECUTING, false, true));
        }
        assertEquals(SUCCEEDED, OverlayPetState.from(STATUS_COMPLETED, -1, true, false).motion());
        assertEquals(FAILED, OverlayPetState.from(STATUS_REJECTED, -1, true, false).motion());
        assertEquals(REVIEW, OverlayPetState.from(STATUS_EXECUTION_UNKNOWN, -1, true, false).motion());
        assertEquals(UNCERTAIN, OverlayPetState.from(STATUS_EXECUTION_UNKNOWN, -1, true, false).indicator());
        assertEquals(IDLE, OverlayPetState.from(STATUS_CANCELLED, -1, true, false).motion());
    }
}
