package com.matrix.agent.evaluation;

import com.matrix.agent.contract.ModelApiException;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.conversation.AssistantReply;
import com.matrix.agent.task.conversation.ConversationAssistantProjector;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.Executors;

/** Isolated device check for the real executor and user-facing failure projection. */
final class ModelFailureDeviceCheck {
    private ModelFailureDeviceCheck() { }

    static JSONObject verify() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try {
            var agentRequest = AgentRequest.builder("synthetic model failure", Actor.DRIVER)
                    .timeoutMillis(10_000).build();
            var request = new ModelTurnRequest(agentRequest, List.of(), List.of(),
                    "synthetic system prompt", new SessionContext());
            ModelGateway gateway = ignored -> {
                throw new IllegalStateException("planner wrapped provider failure",
                        new ModelApiException.RateLimitException("synthetic://provider", null));
            };
            var result = new ModelCallExecutor(1, worker).decide(gateway, request);
            require(!result.isSuccess() && result.getTerminalReason() == StopReason.MODEL_RATE_LIMITED,
                    "wrapped HTTP 429 was not classified as model rate limit");

            var trajectory = new Trajectory();
            trajectory.finish(StopReason.MODEL_RATE_LIMITED, 0, 0);
            var outcome = new AgentOutcome(agentRequest.getRequestId(), TaskState.FAILED,
                    StopReason.MODEL_RATE_LIMITED, trajectory, 0);
            AssistantReply reply = ConversationAssistantProjector.project(outcome,
                    ConversationAssistantProjector.MAX_REPLY_CHARS);
            require(reply.source() == AssistantReply.Source.SYNTHESIZED_TERMINAL
                            && reply.text().contains("HTTP 429")
                            && !reply.text().contains("安全策略"),
                    "rate limit was projected as a policy denial");
            return new JSONObject().put("stopReason", result.getTerminalReason().name())
                    .put("safeUserMessage", true).put("externalRequestSent", false);
        } finally {
            worker.shutdownNow();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
