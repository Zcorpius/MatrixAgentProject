package com.matrix.agent.launcher.overlay;

import static com.matrix.agent.api.conversation.ConversationMessage.*;
import static com.matrix.agent.api.conversation.ConversationRuntimeStage.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Indicator.*;
import static com.matrix.agent.launcher.overlay.pet.PetPresentation.Motion.*;

import com.matrix.agent.launcher.overlay.pet.PetPresentation;

/** Task facts drive the pet; localized progress labels and external-UI input gates do not. */
final class OverlayPetState {
    private OverlayPetState() {}

    static PetPresentation from(OverlayConversationPresenter.State state) {
        return from(state.status(), state.runtimeStage(), state.connected(), state.cancelling());
    }

    static PetPresentation from(int status, int stage, boolean connected, boolean cancelling) {
        // Terminal facts survive reconnects, so a reconnect cannot replay the completion gesture.
        return switch (status) {
            case STATUS_COMPLETED -> new PetPresentation(SUCCEEDED, SUCCESS);
            case STATUS_FAILED, STATUS_REJECTED -> new PetPresentation(FAILED, ERROR);
            case STATUS_EXECUTION_UNKNOWN -> new PetPresentation(REVIEW, UNCERTAIN);
            case STATUS_CANCELLED -> new PetPresentation(IDLE, CANCELLED);
            default -> !connected ? new PetPresentation(WAITING, OFFLINE)
                    : new PetPresentation(cancelling || stage == STAGE_QUEUED ? WAITING
                            : status == STATUS_RUNNING || stage == STAGE_PLANNING || stage == STAGE_EXECUTING
                                    ? WORKING : WAITING, NONE);
        };
    }
}
